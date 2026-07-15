package fr.svpro.radiomercure.podcast;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses MRSS / standard RSS video-podcast feeds. Handles the common
 * variations seen in the wild:
 *  - <media:content url="..." type="..." duration="..."/> (optionally
 *    wrapped in <media:group>, picking the highest quality entry present)
 *  - Plain RSS <enclosure url="..." type="..."/>
 *  - <media:thumbnail url="..."/> or <itunes:image href="..."/>
 *  - <itunes:duration> in HH:MM:SS, MM:SS or seconds form
 *
 * Namespace prefixes are read literally (non namespace-aware parsing) since
 * feeds in the wild are inconsistent about declaring/using them consistently.
 */
public class MrssFeedParser {

    public List<Episode> parse(InputStream inputStream) throws Exception {
        List<Episode> episodes = new ArrayList<>();

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(inputStream, null);

        Episode current = null;
        String currentTag = null;
        String bestMediaUrl = null;
        String bestMediaType = null;
        long bestBitrate = -1;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String name = parser.getName();
                currentTag = name;

                if (equalsLocal(name, "item")) {
                    current = new Episode();
                    bestMediaUrl = null;
                    bestMediaType = null;
                    bestBitrate = -1;
                } else if (current != null && equalsLocal(name, "enclosure")) {
                    String url = attr(parser, "url");
                    String type = attr(parser, "type");
                    if (url != null && current.mediaUrl.isEmpty()) {
                        current.mediaUrl = url;
                        current.mediaType = type != null ? type : "";
                    }
                } else if (current != null && (equalsLocal(name, "content") || name.endsWith(":content"))) {
                    // media:content (possibly repeated inside media:group)
                    String url = attr(parser, "url");
                    String type = attr(parser, "type");
                    String bitrateStr = attr(parser, "bitrate");
                    String durationStr = attr(parser, "duration");
                    long bitrate = parseLongSafe(bitrateStr, -1);

                    if (url != null && bitrate > bestBitrate) {
                        bestBitrate = bitrate;
                        bestMediaUrl = url;
                        bestMediaType = type;
                    }
                    if (durationStr != null && current.durationSeconds < 0) {
                        current.durationSeconds = parseLongSafe(durationStr, -1);
                    }
                } else if (current != null && (equalsLocal(name, "thumbnail") || name.endsWith(":thumbnail"))) {
                    String url = attr(parser, "url");
                    if (url != null && current.thumbnailUrl.isEmpty()) {
                        current.thumbnailUrl = url;
                    }
                } else if (current != null && name.endsWith(":image")) {
                    String href = attr(parser, "href");
                    if (href != null && current.thumbnailUrl.isEmpty()) {
                        current.thumbnailUrl = href;
                    }
                } else if (current != null && equalsLocal(name, "link")) {
                    // Atom-style self-closing <link href="..."/>; the more common
                    // RSS <link>text</link> form is picked up in the TEXT branch below.
                    String href = attr(parser, "href");
                    if (href != null && current.pageUrl.isEmpty()) {
                        current.pageUrl = href;
                    }
                }
            } else if (eventType == XmlPullParser.TEXT) {
                String text = parser.getText();
                if (current != null && text != null && !text.trim().isEmpty()) {
                    String value = text.trim();
                    if (equalsLocal(currentTag, "title") && current.title.isEmpty()) {
                        current.title = value;
                    } else if (equalsLocal(currentTag, "description") && current.description.isEmpty()) {
                        current.description = value;
                    } else if (equalsLocal(currentTag, "pubDate") && current.pubDate.isEmpty()) {
                        current.pubDate = value;
                    } else if (equalsLocal(currentTag, "link") && current.pageUrl.isEmpty()) {
                        current.pageUrl = value;
                    } else if (currentTag != null && currentTag.endsWith(":duration")
                            && current.durationSeconds < 0) {
                        current.durationSeconds = parseDurationToSeconds(value);
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if (equalsLocal(name, "item") && current != null) {
                    if (current.mediaUrl.isEmpty() && bestMediaUrl != null) {
                        current.mediaUrl = bestMediaUrl;
                        current.mediaType = bestMediaType != null ? bestMediaType : "";
                    }
                    if (!current.mediaUrl.isEmpty()) {
                        episodes.add(current);
                    }
                    current = null;
                }
                currentTag = null;
            }
            eventType = parser.next();
        }

        return episodes;
    }

    private static boolean equalsLocal(String tagName, String local) {
        if (tagName == null) return false;
        int idx = tagName.indexOf(':');
        String stripped = idx >= 0 ? tagName.substring(idx + 1) : tagName;
        return stripped.equalsIgnoreCase(local);
    }

    private static String attr(XmlPullParser parser, String name) {
        String v = parser.getAttributeValue(null, name);
        if (v != null) return v;
        // fall back to scanning attributes for a namespaced match e.g. media:url (rare)
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attrName = parser.getAttributeName(i);
            if (equalsLocal(attrName, name)) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseDurationToSeconds(String value) {
        try {
            if (value.contains(":")) {
                String[] parts = value.split(":");
                long seconds = 0;
                for (String part : parts) {
                    seconds = seconds * 60 + Long.parseLong(part.trim());
                }
                return seconds;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
