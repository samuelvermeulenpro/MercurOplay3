package fr.svpro.radiomercure.peertube;

import java.io.Serializable;

/** A video channel owned by the authenticated PeerTube account. */
public class PtChannel implements Serializable {

    public String name = "";          // channel handle, e.g. "my_channel" - used in API calls
    public String displayName = "";
    public String description = "";
    public String avatarUrl = "";      // resolved absolute URL, may be empty
    public int videosCount = -1;       // -1 = unknown until videos are actually listed
}
