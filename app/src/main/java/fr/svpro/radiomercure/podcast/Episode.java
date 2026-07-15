package fr.svpro.radiomercure.podcast;

import java.io.Serializable;

public class Episode implements Serializable {

    public String title = "";
    public String description = "";
    public String pubDate = "";
    public String mediaUrl = "";
    public String mediaType = ""; // e.g. video/mp4, audio/mpeg
    public String thumbnailUrl = "";
    public String pageUrl = ""; // RSS <link> - the episode's web page, used for sharing
    public long durationSeconds = -1;

    public boolean isVideo() {
        if (mediaType != null && mediaType.toLowerCase().startsWith("video")) return true;
        if (mediaUrl == null) return false;
        String lower = mediaUrl.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".mov")
                || lower.contains(".webm");
    }
}
