package fr.svpro.radiomercure.peertube;

import java.io.Serializable;

/** A single video, as returned by the video-channel videos listing endpoint. */
public class PtVideo implements Serializable {

    public String id = "";            // numeric video id, used to fetch download files on demand
    public String name = "";
    public String shortUUID = "";
    public String thumbnailUrl = "";  // resolved absolute URL, may be empty
    public String pageUrl = "";       // {instance}/w/{shortUUID} - used for sharing, no extra API call needed
    public String description = "";   // truncatedDescription from the list endpoint
    public String publishedAt = "";   // ISO date string, as returned by the API
    public long durationSeconds = -1;
    public boolean downloadEnabled = true;
}
