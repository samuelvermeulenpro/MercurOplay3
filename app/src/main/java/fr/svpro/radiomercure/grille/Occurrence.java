package fr.svpro.radiomercure.grille;

/** One concrete airing of a show in the schedule (a recurring event expanded). */
public class Occurrence {

    public final String uid;
    public final long start;
    public final long end;
    public final String title;
    public final String description;
    public final String link;
    public final String image;
    public final String host;

    Occurrence(IcsEvent e, long start, String description, String host) {
        this.uid = e.uid + "@" + start;
        this.start = start;
        this.end = start + e.durationSec;
        this.title = e.title;
        this.description = description;
        this.link = e.link.matches("(?i)https?://\\S+") ? e.link : "";
        this.image = e.place.matches("(?i)https?://\\S+") ? e.place : "";
        this.host = host;
    }

    public boolean isOnAirAt(long epochSec) {
        return start <= epochSec && epochSec < end;
    }
}
