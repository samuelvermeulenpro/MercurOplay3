package fr.svpro.radiomercure.grille;

import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * A VEVENT from the Nextcloud calendar, normalized (times as epoch seconds). Recurring
 * events are kept as a single entry with their RRULE - see {@link IcsExpander} for
 * turning them into concrete occurrences.
 */
public class IcsEvent {

    public final String uid;
    public final String title;
    public final String description;
    /** URL field: the show's page (usually on MercurOplay). */
    public final String link;
    /** LOCATION field: used as the show's thumbnail URL, same as the website. */
    public final String place;
    public final long start;
    public final ZoneId zone;
    public final long durationSec;
    public final boolean allDay;
    /** Parsed RRULE parts (upper-cased), UNTIL converted to epoch seconds; null if none. */
    public final Map<String, String> rrule;
    public final Long until;
    public final Set<Long> exdates;
    /** RECURRENCE-ID: original start of the occurrence this event overrides, or null. */
    public final Long recurrenceId;
    public final boolean cancelled;

    IcsEvent(String uid, String title, String description, String link, String place,
             long start, ZoneId zone, long durationSec, boolean allDay,
             Map<String, String> rrule, Long until, Set<Long> exdates,
             Long recurrenceId, boolean cancelled) {
        this.uid = uid;
        this.title = title;
        this.description = description;
        this.link = link;
        this.place = place;
        this.start = start;
        this.zone = zone;
        this.durationSec = durationSec;
        this.allDay = allDay;
        this.rrule = rrule;
        this.until = until;
        this.exdates = exdates;
        this.recurrenceId = recurrenceId;
        this.cancelled = cancelled;
    }
}
