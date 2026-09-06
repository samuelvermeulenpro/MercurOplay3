package fr.svpro.radiomercure.peertube;

import java.io.Serializable;

/** A video channel owned by the authenticated PeerTube account. */
public class PtChannel implements Serializable {

    public String name = "";          // channel handle, e.g. "my_channel" - used in API calls
    public String displayName = "";
    public String description = "";
    public String avatarUrl = "";      // resolved absolute URL, may be empty
    public int videosCount = -1;       // -1 = unknown until videos are actually listed
    // ISO 8601 UTC timestamp (e.g. "2023-01-15T10:30:00.000Z") as returned by the API.
    // Sorts correctly with plain string comparison since PeerTube always returns it
    // zero-padded and in UTC - no date parsing needed. Empty if missing/unparseable.
    public String createdAt = "";
}
