package fr.svpro.radiomercure.grille;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands calendar events into concrete occurrences, ported from the WordPress theme
 * (rm_ics_expand / rm_rrule_starts): DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT,
 * UNTIL, BYDAY (with ordinals like 4SA / -1SU), BYMONTHDAY, BYMONTH, EXDATE, moved or
 * cancelled occurrences (RECURRENCE-ID) and DST changes (occurrences keep their local
 * wall-clock time in the event's own time zone).
 */
public final class IcsExpander {

    /** "Animation : Prénom Nom" line in the description, as read by the website. */
    private static final Pattern HOST = Pattern.compile(
            "^\\s*(?:anim\\w*|pr[ée]sent\\w*)(?:\\s*·\\s*rice)?\\s*:\\s*(.+)$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private IcsExpander() {
    }

    /** Occurrences overlapping [from, to[ (epoch seconds), sorted by start. */
    public static List<Occurrence> expand(List<IcsEvent> events, long from, long to) {
        Set<String> skip = new HashSet<>(); // replaced/cancelled occurrences: uid|original start.
        for (IcsEvent e : events) {
            if (e.recurrenceId != null) skip.add(e.uid + "|" + e.recurrenceId);
        }
        List<Occurrence> out = new ArrayList<>();
        for (IcsEvent e : events) {
            if (e.allDay || e.cancelled) continue;
            String description = cleanDescription(e.description);
            String host = hostOf(description);
            if (e.rrule == null || e.recurrenceId != null) {
                if (e.start < to && e.start + e.durationSec > from) {
                    out.add(new Occurrence(e, e.start, description, host));
                }
                continue;
            }
            for (long ts : rruleStarts(e, from - e.durationSec, to)) {
                if (skip.contains(e.uid + "|" + ts) || e.exdates.contains(ts)) continue;
                if (ts < to && ts + e.durationSec > from) {
                    out.add(new Occurrence(e, ts, description, host));
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static String hostOf(String description) {
        Matcher m = HOST.matcher(description);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * Nextcloud may store the description as HTML: keep its link targets, turn line breaks
     * into new lines and drop the remaining tags, as the website does.
     */
    static String cleanDescription(String desc) {
        if (desc.isEmpty() || !desc.contains("<")) return desc;
        String text = desc
                .replaceAll("(?i)<br\\s*/?>|</p>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        // Link targets hidden behind anchor text stay reachable (made clickable by autoLink).
        StringBuilder sb = new StringBuilder(text);
        Matcher m = HREF.matcher(desc);
        while (m.find()) {
            String url = m.group(1).replace("&amp;", "&");
            if (sb.indexOf(url) < 0) sb.append('\n').append(url);
        }
        return sb.toString().replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Start times of an RRULE's occurrences between from and to. */
    static List<Long> rruleStarts(IcsEvent e, long from, long to) {
        String freq = e.rrule.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(e.rrule.get("INTERVAL"), 1));
        Integer count = e.rrule.containsKey("COUNT") ? parseInt(e.rrule.get("COUNT"), 0) : null;
        Long until = e.until;

        ZonedDateTime dt0 = Instant.ofEpochSecond(e.start).atZone(e.zone);
        LocalTime time = dt0.toLocalTime();
        LocalDate day0 = dt0.toLocalDate();

        List<int[]> byDay = new ArrayList<>(); // [ordinal or 0, ISO day-of-week].
        String rawByDay = e.rrule.get("BYDAY");
        if (rawByDay != null && !rawByDay.isEmpty()) {
            Pattern p = Pattern.compile("^([+-]?\\d+)?(MO|TU|WE|TH|FR|SA|SU)$");
            for (String bd : rawByDay.split(",")) {
                Matcher m = p.matcher(bd);
                if (m.matches()) {
                    byDay.add(new int[]{parseInt(m.group(1), 0), dayCode(m.group(2))});
                }
            }
        }
        List<Integer> byMonthDay = intList(e.rrule.get("BYMONTHDAY"));
        List<Integer> byMonth = intList(e.rrule.get("BYMONTH"));
        if ("WEEKLY".equals(freq) && byDay.isEmpty()) {
            byDay.add(new int[]{0, day0.getDayOfWeek().getValue()});
        }

        // Without COUNT there's no need to walk from the very start of the series.
        LocalDate cursor = day0;
        if (count == null && from - 2 * 86400L > e.start) {
            cursor = Instant.ofEpochSecond(from - 2 * 86400L).atZone(e.zone).toLocalDate();
        }
        LocalDate monday0 = day0.with(DayOfWeek.MONDAY);

        List<Long> starts = new ArrayList<>();
        int n = 0;
        for (int guard = 0; guard < 40000; guard++, cursor = cursor.plusDays(1)) {
            long cts = cursor.atStartOfDay(e.zone).toEpochSecond();
            if (cts >= to || (until != null && cts > until)) break;
            int wd = cursor.getDayOfWeek().getValue();
            int md = cursor.getDayOfMonth();
            int mo = cursor.getMonthValue();
            if (!byMonth.isEmpty() && !byMonth.contains(mo)) continue;

            boolean ok = false;
            switch (freq) {
                case "DAILY":
                    ok = ChronoUnit.DAYS.between(day0, cursor) % interval == 0
                            && (byDay.isEmpty() || hasWeekday(byDay, wd));
                    break;
                case "WEEKLY":
                    long weeks = ChronoUnit.WEEKS.between(monday0, cursor.with(DayOfWeek.MONDAY));
                    ok = weeks % interval == 0 && hasWeekday(byDay, wd);
                    break;
                case "MONTHLY": {
                    long months = (cursor.getYear() - day0.getYear()) * 12L + mo - day0.getMonthValue();
                    if (months % interval != 0) break;
                    if (!byMonthDay.isEmpty()) {
                        ok = monthDayMatch(md, cursor.lengthOfMonth(), byMonthDay);
                    } else if (!byDay.isEmpty()) {
                        ok = nthMatch(cursor, byDay);
                    } else {
                        ok = md == day0.getDayOfMonth();
                    }
                    break;
                }
                case "YEARLY": {
                    if ((cursor.getYear() - day0.getYear()) % interval != 0) break;
                    boolean sameMonth = !byMonth.isEmpty() || mo == day0.getMonthValue();
                    if (!byDay.isEmpty() && !byMonth.isEmpty()) {
                        ok = nthMatch(cursor, byDay);
                    } else if (!byMonthDay.isEmpty()) {
                        ok = sameMonth && monthDayMatch(md, cursor.lengthOfMonth(), byMonthDay);
                    } else {
                        ok = sameMonth && md == day0.getDayOfMonth();
                    }
                    break;
                }
                default:
                    break;
            }
            if (!ok) continue;

            long ts = cursor.atTime(time).atZone(e.zone).toEpochSecond();
            if (ts < e.start) continue;
            if (until != null && ts > until) break;
            n++;
            if (count != null && n > count) break;
            if (ts + e.durationSec > from) starts.add(ts);
        }
        return starts;
    }

    private static boolean hasWeekday(List<int[]> byDay, int wd) {
        for (int[] b : byDay) {
            if (b[1] == wd) return true;
        }
        return false;
    }

    private static boolean monthDayMatch(int md, int daysInMonth, List<Integer> list) {
        for (int d : list) {
            if (d == md || (d < 0 && daysInMonth + d + 1 == md)) return true;
        }
        return false;
    }

    /** BYDAY with an in-month ordinal (1SA = first Saturday, -1SU = last Sunday, SA = every Saturday). */
    private static boolean nthMatch(LocalDate d, List<int[]> byDay) {
        int wd = d.getDayOfWeek().getValue();
        int md = d.getDayOfMonth();
        int dim = d.lengthOfMonth();
        for (int[] b : byDay) {
            if (b[1] != wd) continue;
            if (b[0] == 0) return true;
            int nthFromStart = (md - 1) / 7 + 1;
            int nthFromEnd = -((dim - md) / 7 + 1);
            if (b[0] == nthFromStart || b[0] == nthFromEnd) return true;
        }
        return false;
    }

    private static int dayCode(String code) {
        switch (code) {
            case "MO": return 1;
            case "TU": return 2;
            case "WE": return 3;
            case "TH": return 4;
            case "FR": return 5;
            case "SA": return 6;
            default: return 7;
        }
    }

    private static List<Integer> intList(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            out.add(parseInt(s, 0));
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim().replace("+", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
