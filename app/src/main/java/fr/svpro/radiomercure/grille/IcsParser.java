package fr.svpro.radiomercure.grille;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iCalendar (RFC 5545) parser - the subset useful for a radio schedule, ported from the
 * Radio Mercure WordPress theme (inc/calendar.php) so the app and the website read the
 * Nextcloud "rivendell" calendar the same way.
 */
public final class IcsParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final Pattern DURATION = Pattern.compile(
            "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

    private IcsParser() {
    }

    /** A raw property value with its parameters (TZID, VALUE…). */
    private static final class Prop {
        final String value;
        final Map<String, String> params;

        Prop(String value, Map<String, String> params) {
            this.value = value;
            this.params = params;
        }
    }

    /** Parses a whole VCALENDAR; floating times are read in {@code defaultZone}. */
    public static List<IcsEvent> parse(String ics, ZoneId defaultZone) {
        ics = ics.replace("\r\n", "\n").replace('\r', '\n');
        ics = ics.replaceAll("\n[ \t]", ""); // unfold continuation lines.

        List<IcsEvent> events = new ArrayList<>();
        Map<String, Object> ev = null;
        int depth = 0; // skips VALARM and other sub-components.
        for (String line : ics.split("\n")) {
            if (line.isEmpty()) continue;
            if (line.equals("BEGIN:VEVENT")) {
                ev = new HashMap<>();
                ev.put("EXDATE", new ArrayList<Prop>());
                depth = 0;
                continue;
            }
            if (ev == null) continue;
            if (line.startsWith("BEGIN:")) {
                depth++;
                continue;
            }
            if (line.equals("END:VEVENT")) {
                IcsEvent norm = normalize(ev, defaultZone);
                if (norm != null) events.add(norm);
                ev = null;
                continue;
            }
            if (line.startsWith("END:")) {
                depth--;
                continue;
            }
            if (depth > 0) continue;

            int colon = findValueColon(line);
            if (colon < 0) continue;
            String[] head = line.substring(0, colon).split(";");
            String name = head[0].toUpperCase(Locale.ROOT);
            Map<String, String> params = new HashMap<>();
            for (int i = 1; i < head.length; i++) {
                String[] kv = head[i].split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].toUpperCase(Locale.ROOT), kv[1].replace("\"", ""));
                }
            }
            String value = line.substring(colon + 1);
            switch (name) {
                case "SUMMARY":
                case "DESCRIPTION":
                case "LOCATION":
                    ev.put(name, unescape(value));
                    break;
                case "UID":
                case "STATUS":
                case "RRULE":
                case "DURATION":
                case "URL":
                    ev.put(name, value);
                    break;
                case "DTSTART":
                case "DTEND":
                case "RECURRENCE-ID":
                    ev.put(name, new Prop(value, params));
                    break;
                case "EXDATE":
                    @SuppressWarnings("unchecked")
                    List<Prop> ex = (List<Prop>) ev.get("EXDATE");
                    for (String v : value.split(",")) ex.add(new Prop(v, params));
                    break;
                default:
                    break;
            }
        }
        return events;
    }

    /** Position of the name/value separator, ignoring colons inside quoted parameters. */
    private static int findValueColon(String line) {
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quote = !quote;
            } else if (c == ':' && !quote) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Date/time value → zoned date-time, plus whether it's an all-day (DATE) value. */
    private static ZonedDateTime parseDate(String value, Map<String, String> params, ZoneId defaultZone, boolean[] allDay) {
        value = value.trim();
        try {
            if ("DATE".equalsIgnoreCase(params.get("VALUE")) || value.matches("\\d{8}")) {
                if (allDay != null) allDay[0] = true;
                return LocalDate.parse(value.substring(0, 8), DATE).atStartOfDay(defaultZone);
            }
            if (value.endsWith("Z")) {
                return LocalDateTime.parse(value.substring(0, value.length() - 1), DATE_TIME)
                        .atZone(ZoneOffset.UTC).withZoneSameInstant(defaultZone);
            }
            ZoneId zone = defaultZone;
            String tzid = params.get("TZID");
            if (tzid != null && !tzid.isEmpty()) {
                try {
                    zone = ZoneId.of(tzid.replaceFirst("^/.*?/", ""));
                } catch (Exception ignored) {
                    zone = defaultZone;
                }
            }
            return LocalDateTime.parse(value, DATE_TIME).atZone(zone);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** ISO 8601 duration (P1DT2H30M…) in seconds. */
    static long parseDuration(String dur) {
        Matcher m = DURATION.matcher(dur.trim());
        if (!m.matches()) return 0;
        long s = num(m.group(2)) * 604800 + num(m.group(3)) * 86400
                + num(m.group(4)) * 3600 + num(m.group(5)) * 60 + num(m.group(6));
        return "-".equals(m.group(1)) ? -s : s;
    }

    private static long num(String g) {
        return g == null ? 0 : Long.parseLong(g);
    }

    @SuppressWarnings("unchecked")
    private static IcsEvent normalize(Map<String, Object> ev, ZoneId defaultZone) {
        Prop dtstart = (Prop) ev.get("DTSTART");
        if (dtstart == null) return null;
        boolean[] allDay = {false};
        ZonedDateTime start = parseDate(dtstart.value, dtstart.params, defaultZone, allDay);
        if (start == null) return null;

        long dur;
        Prop dtend = (Prop) ev.get("DTEND");
        if (dtend != null) {
            ZonedDateTime end = parseDate(dtend.value, dtend.params, defaultZone, null);
            dur = end != null ? end.toEpochSecond() - start.toEpochSecond() : 0;
        } else if (ev.get("DURATION") != null) {
            dur = parseDuration((String) ev.get("DURATION"));
        } else {
            dur = allDay[0] ? 86400 : 0;
        }

        Map<String, String> rrule = null;
        Long until = null;
        String rawRule = (String) ev.get("RRULE");
        if (rawRule != null && !rawRule.isEmpty()) {
            rrule = new HashMap<>();
            for (String part : rawRule.split(";")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    rrule.put(kv[0].toUpperCase(Locale.ROOT), kv[1].toUpperCase(Locale.ROOT));
                }
            }
            String u = rrule.get("UNTIL");
            if (u != null) {
                Map<String, String> p = new HashMap<>();
                p.put("TZID", start.getZone().getId());
                ZonedDateTime ud = parseDate(u, p, defaultZone, null);
                if (ud != null) {
                    // A date-only UNTIL includes that whole day.
                    until = ud.toEpochSecond() + (u.matches("\\d{8}") ? 86400 - 1 : 0);
                }
            }
        }

        Set<Long> exdates = new HashSet<>();
        for (Prop x : (List<Prop>) ev.get("EXDATE")) {
            ZonedDateTime d = parseDate(x.value, x.params, defaultZone, null);
            if (d != null) exdates.add(d.toEpochSecond());
        }

        Long rid = null;
        Prop ridProp = (Prop) ev.get("RECURRENCE-ID");
        if (ridProp != null) {
            ZonedDateTime r = parseDate(ridProp.value, ridProp.params, defaultZone, null);
            rid = r != null ? r.toEpochSecond() : null;
        }

        String uid = (String) ev.get("UID");
        if (uid == null) uid = Integer.toHexString(ev.toString().hashCode());

        return new IcsEvent(
                uid,
                str(ev, "SUMMARY"),
                str(ev, "DESCRIPTION"),
                str(ev, "URL"),
                str(ev, "LOCATION"),
                start.toEpochSecond(),
                start.getZone(),
                Math.max(0, dur),
                allDay[0],
                rrule,
                until,
                exdates,
                rid,
                "CANCELLED".equalsIgnoreCase(str(ev, "STATUS")));
    }

    private static String str(Map<String, Object> ev, String key) {
        Object v = ev.get(key);
        return v == null ? "" : ((String) v).trim();
    }
}
