package fr.svpro.radiomercure.util;

public final class Config {

    private Config() {
    }

    /** Live Icecast stream (MP3/AAC with ICY metadata). */
    public static final String LIVE_STREAM_URL = "https://oplay-stream.radiomercure.net/live";

    /**
     * Icecast JSON status endpoint used to read the live listener count.
     * Standard Icecast2 servers expose this at the host root; if the mount
     * itself changes the listeners count is read from the matching source
     * entry, with a safe fallback to "unknown" if the endpoint is unavailable.
     */
    public static final String LIVE_STATUS_URL = "https://oplay-stream.radiomercure.net/status-json.xsl";

    /** Podcast / video MRSS feed. */
    public static final String PODCAST_FEED_URL = "https://oplay.radiomercure.fr/feeds/videos.xml?accountId=4";

    /** iTunes Search API - no API key required. */
    public static final String ITUNES_SEARCH_URL = "https://itunes.apple.com/search";

    public static final long LISTENER_POLL_INTERVAL_MS = 15_000L;
}
