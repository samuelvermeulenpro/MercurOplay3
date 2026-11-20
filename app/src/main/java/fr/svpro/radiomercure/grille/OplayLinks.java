package fr.svpro.radiomercure.grille;

import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes MercurOplay (PeerTube) links found in the calendar, to open them in the app. */
final class OplayLinks {

    /** Video page: …/w/SHORTUUID or legacy …/videos/watch/UUID (playlists …/w/p/… excluded). */
    private static final Pattern VIDEO = Pattern.compile(
            "(?i)^https?://[^/]*oplay[^/]*/(?:w|videos/watch)/(?!p/)([A-Za-z0-9-]+)");
    /** Channel page: …/c/NAME, …/video-channels/NAME or …/@NAME. */
    private static final Pattern CHANNEL = Pattern.compile(
            "(?i)^https?://[^/]*oplay[^/]*/(?:c/|video-channels/|@)([^/?#]+)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\"']+");

    private OplayLinks() {
    }

    static boolean isOplay(String url) {
        return url.matches("(?i)^https?://[^/]*oplay[^/]*(/.*)?$");
    }

    /** Video id (shortUUID or UUID) of a replay link, or null. */
    static String videoId(String url) {
        Matcher m = VIDEO.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Channel name of a channel link, or null. */
    static String channelName(String url) {
        Matcher m = CHANNEL.matcher(url);
        return m.find() ? Uri.decode(m.group(1)) : null;
    }

    /** First replay link in a description (the website turns it into an embedded player). */
    static String firstReplay(String text) {
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,;:!?)]+$", "");
            if (videoId(url) != null) return url;
        }
        return null;
    }
}
