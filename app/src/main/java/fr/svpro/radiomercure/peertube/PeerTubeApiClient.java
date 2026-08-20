package fr.svpro.radiomercure.peertube;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import fr.svpro.radiomercure.util.Config;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Thin client for the parts of the PeerTube REST API this app needs: listing the
 * authenticated user's channels, listing a channel's videos, resolving a video's best
 * downloadable file, and resolving a video's best in-app playback source.
 *
 * <p>Every call authenticates with a Bearer access token from {@link PeerTubeAuthStore}.
 * On a 401 <i>or</i> 403 response (some PeerTube/OAuth2 middleware configurations report
 * an expired token as 403 rather than 401), the access token is transparently refreshed
 * (via {@code client_id}/{@code client_secret} + the stored refresh token,
 * {@code POST /users/token} with {@code grant_type=refresh_token}) and the original call
 * is retried once. If the refresh itself fails, the concrete reason (HTTP status, network
 * error, or malformed response) is logged (tag "PeerTubeApiClient") and surfaced in the
 * error message passed back to the UI, rather than a generic failure - so a future failure
 * report only needs a logcat capture or a screenshot of the error, not more guessing.
 *
 * <p><b>Singleton</b>: obtained via {@link #getInstance(Context)} rather than constructed
 * directly. PeerTube rotates the refresh token on every use (the response to a successful
 * refresh includes a *new* refresh token, invalidating the previous one). Several screens
 * in this app (channels, videos, player) each used to create their own client/OkHttpClient,
 * so if two calls from different screens hit a 401 around the same time, both would read
 * the same still-current refresh token from storage and each fire an independent refresh
 * request; the first would succeed and rotate the token, but the second - now using an
 * already-consumed refresh token - would be rejected, and PeerTube's reuse-detection can
 * respond to that by revoking the whole token family, which is exactly what required
 * regenerating everything by hand server-side. Routing every screen through one shared
 * instance lets {@link #refreshAccessToken} coalesce concurrent 401s into a single
 * in-flight refresh call that every caller then waits on and reuses.
 *
 * <p>Field names below are deliberately tolerant of two PeerTube API generations:
 * the modern {@code thumbnails[]} / {@code avatars[]} array (PeerTube >= 7.1/8.1) is
 * preferred, with a fallback to the older, now-deprecated {@code thumbnailPath}/{@code path}
 * relative fields (prefixed with the instance URL) for older server versions.
 */
public class PeerTubeApiClient {

    private static final String TAG = "PeerTubeApiClient";

    private static volatile PeerTubeApiClient instance;

    public static PeerTubeApiClient getInstance(Context context) {
        if (instance == null) {
            synchronized (PeerTubeApiClient.class) {
                if (instance == null) {
                    instance = new PeerTubeApiClient(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public interface ChannelsCallback {
        void onSuccess(List<PtChannel> channels);
        void onError(String message);
    }

    public interface VideosCallback {
        void onSuccess(List<PtVideo> videos);
        void onError(String message);
    }

    public interface DownloadUrlCallback {
        void onSuccess(String downloadUrl, String suggestedFileName);
        void onError(String message);
    }

    public interface PlaybackSourceCallback {
        void onSuccess(String playbackUri);
        void onError(String message);
    }

    /** Internal contract for a single authed GET call, with transparent 401 retry. */
    private interface RawResultHandler {
        void onResponse(Response response) throws Exception;
        void onError(String message);
    }

    private final OkHttpClient client;
    private final PeerTubeAuthStore authStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // --- Refresh coalescing: see class javadoc. Guards against concurrent refresh calls
    // racing on the same (about-to-be-rotated) refresh token.
    private final Object refreshLock = new Object();
    private boolean refreshInFlight = false;
    private final List<Runnable> queuedOnSuccess = new ArrayList<>();
    private final List<RefreshFailureHandler> queuedOnFailure = new ArrayList<>();

    /** Carries a concrete, loggable reason a refresh failed (HTTP code, network error, etc.). */
    private interface RefreshFailureHandler {
        void onFailure(String reason);
    }

    PeerTubeApiClient(Context appContext) {
        this.client = new OkHttpClient();
        this.authStore = new PeerTubeAuthStore(appContext);
    }

    // --- Public API ------------------------------------------------------------------

    /** Fetches every video channel owned by the authenticated user via GET /users/me. */
    public void fetchChannels(ChannelsCallback callback) {
        getWithAuth(Config.PEERTUBE_API_BASE_URL + "/users/me", new RawResultHandler() {
            @Override
            public void onResponse(Response response) throws Exception {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                    return;
                }
                JSONObject user = firstObjectOrSelf(response.body().string());
                JSONArray channelsJson = user.optJSONArray("videoChannels");
                List<PtChannel> channels = new ArrayList<>();
                if (channelsJson != null) {
                    for (int i = 0; i < channelsJson.length(); i++) {
                        channels.add(parseChannel(channelsJson.getJSONObject(i)));
                    }
                }
                mainHandler.post(() -> callback.onSuccess(channels));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    /** Fetches the videos of a single channel via GET /video-channels/{name}/videos. */
    public void fetchChannelVideos(String channelName, VideosCallback callback) {
        String url = Config.PEERTUBE_API_BASE_URL + "/video-channels/" + channelName
                + "/videos?count=100&sort=-publishedAt";
        getWithAuth(url, new RawResultHandler() {
            @Override
            public void onResponse(Response response) throws Exception {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                    return;
                }
                JSONObject json = new JSONObject(response.body().string());
                JSONArray data = json.optJSONArray("data");
                List<PtVideo> videos = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        videos.add(parseVideo(data.getJSONObject(i)));
                    }
                }
                mainHandler.post(() -> callback.onSuccess(videos));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    /**
     * Resolves the best available direct-download URL for a video by fetching its full
     * details (GET /videos/{id}), which - unlike the list endpoints - includes the actual
     * {@code files} (Web Video) and {@code streamingPlaylists} (HLS) arrays. Picks the
     * highest-resolution Web Video file if the server has Web Video enabled, otherwise
     * falls back to the highest-resolution HLS rendition.
     */
    public void fetchDownloadUrl(String videoId, DownloadUrlCallback callback) {
        getWithAuth(Config.PEERTUBE_API_BASE_URL + "/videos/" + videoId, new RawResultHandler() {
            @Override
            public void onResponse(Response response) throws Exception {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                    return;
                }
                JSONObject json = new JSONObject(response.body().string());

                if (!json.optBoolean("downloadEnabled", true)) {
                    mainHandler.post(() -> callback.onError("Téléchargement désactivé par le créateur"));
                    return;
                }

                String bestUrl = bestFileUrl(json.optJSONArray("files"));
                if (bestUrl == null) {
                    JSONArray playlists = json.optJSONArray("streamingPlaylists");
                    if (playlists != null && playlists.length() > 0) {
                        bestUrl = bestFileUrl(playlists.getJSONObject(0).optJSONArray("files"));
                    }
                }

                if (bestUrl == null) {
                    mainHandler.post(() -> callback.onError("Aucun fichier téléchargeable trouvé pour cette vidéo"));
                    return;
                }

                String name = json.optString("name", "video");
                String fileName = sanitizeFileName(name) + ".mp4";
                String finalUrl = bestUrl;
                mainHandler.post(() -> callback.onSuccess(finalUrl, fileName));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    /**
     * Resolves the best URI for in-app playback via ExoPlayer: the HLS master playlist
     * ({@code streamingPlaylists[0].playlistUrl}) when available, for adaptive streaming,
     * falling back to the highest-resolution progressive Web Video file otherwise. Both
     * are directly playable by Media3 (HLS support is already included in the app).
     */
    public void fetchPlaybackSource(String videoId, PlaybackSourceCallback callback) {
        getWithAuth(Config.PEERTUBE_API_BASE_URL + "/videos/" + videoId, new RawResultHandler() {
            @Override
            public void onResponse(Response response) throws Exception {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                    return;
                }
                JSONObject json = new JSONObject(response.body().string());

                String uri = null;
                JSONArray playlists = json.optJSONArray("streamingPlaylists");
                if (playlists != null && playlists.length() > 0) {
                    String playlistUrl = playlists.getJSONObject(0).optString("playlistUrl", null);
                    if (playlistUrl != null && !playlistUrl.isEmpty()) {
                        uri = playlistUrl;
                    }
                }
                if (uri == null) {
                    uri = bestFileUrl(json.optJSONArray("files"));
                }

                if (uri == null) {
                    mainHandler.post(() -> callback.onError("Aucune source de lecture trouvée pour cette vidéo"));
                    return;
                }

                String finalUri = uri;
                mainHandler.post(() -> callback.onSuccess(finalUri));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> callback.onError(message));
            }
        });
    }

    // --- Auth-aware request plumbing --------------------------------------------------

    private Request.Builder authedRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + authStore.getAccessToken());
    }

    private void getWithAuth(String url, RawResultHandler handler) {
        getWithAuthInternal(url, handler, false);
    }

    private void getWithAuthInternal(String url, RawResultHandler handler, boolean isRetry) {
        Request request = authedRequest(url).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Network failure calling " + url + ": " + e.getMessage());
                handler.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                int code = response.code();
                boolean looksLikeAuthFailure = code == 401 || code == 403;
                if (looksLikeAuthFailure && !isRetry) {
                    Log.d(TAG, "Authed GET " + url + " -> HTTP " + code + ", attempting token refresh");
                    response.close();
                    refreshAccessToken(
                            () -> getWithAuthInternal(url, handler, true),
                            reason -> handler.onError(
                                    "Session PeerTube expirée - reconnexion impossible (" + reason + ")"));
                    return;
                }
                try (Response r = response) {
                    handler.onResponse(r);
                } catch (Exception e) {
                    handler.onError(e.getMessage());
                }
            }
        });
    }

    /**
     * POST /users/token with grant_type=refresh_token; persists the new tokens on success.
     * Coalesces concurrent callers: if a refresh is already in flight, this queues the
     * given callbacks behind it instead of firing a second, racing request - see the class
     * javadoc for why that matters (PeerTube rotates the refresh token on every use).
     */
    private void refreshAccessToken(Runnable onSuccess, RefreshFailureHandler onFailure) {
        synchronized (refreshLock) {
            queuedOnSuccess.add(onSuccess);
            queuedOnFailure.add(onFailure);
            if (refreshInFlight) {
                Log.d(TAG, "Refresh already in flight, queuing this caller behind it");
                return;
            }
            refreshInFlight = true;
        }

        FormBody formBody = new FormBody.Builder()
                .add("client_id", Config.PEERTUBE_CLIENT_ID)
                .add("client_secret", Config.PEERTUBE_CLIENT_SECRET)
                .add("grant_type", "refresh_token")
                .add("refresh_token", authStore.getRefreshToken())
                .build();
        Request request = new Request.Builder()
                .url(Config.PEERTUBE_API_BASE_URL + "/users/token")
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Refresh call network failure: " + e.getMessage());
                completeRefresh(false, "réseau : " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        String bodySnippet = safeBodySnippet(r);
                        Log.w(TAG, "Refresh call failed: HTTP " + r.code() + " body=" + bodySnippet);
                        completeRefresh(false, "HTTP " + r.code());
                        return;
                    }
                    JSONObject json = new JSONObject(r.body().string());
                    String newAccessToken = json.optString("access_token", null);
                    String newRefreshToken = json.optString("refresh_token", null);
                    if (newAccessToken == null || newAccessToken.isEmpty()) {
                        Log.w(TAG, "Refresh call returned HTTP " + r.code() + " but no access_token in body");
                        completeRefresh(false, "réponse sans access_token");
                        return;
                    }
                    Log.d(TAG, "Refresh succeeded" + (newRefreshToken != null && !newRefreshToken.isEmpty()
                            ? " (refresh token rotated)" : " (refresh token unchanged)"));
                    authStore.saveTokens(newAccessToken, newRefreshToken);
                    completeRefresh(true, null);
                } catch (Exception e) {
                    Log.w(TAG, "Refresh call response parsing failed: " + e.getMessage());
                    completeRefresh(false, "réponse invalide : " + e.getMessage());
                }
            }
        });
    }

    /** Best-effort peek at a failed response body for logging, without risking a crash. */
    private String safeBodySnippet(Response response) {
        try {
            if (response.body() == null) return "(vide)";
            String body = response.body().string();
            return body.length() > 300 ? body.substring(0, 300) + "…" : body;
        } catch (Exception e) {
            return "(illisible)";
        }
    }

    /** Drains the queued callbacks accumulated while the single in-flight refresh ran. */
    private void completeRefresh(boolean success, String failureReason) {
        List<Runnable> successCallbacks;
        List<RefreshFailureHandler> failureCallbacks;
        synchronized (refreshLock) {
            refreshInFlight = false;
            successCallbacks = new ArrayList<>(queuedOnSuccess);
            failureCallbacks = new ArrayList<>(queuedOnFailure);
            queuedOnSuccess.clear();
            queuedOnFailure.clear();
        }
        if (success) {
            for (Runnable r : successCallbacks) r.run();
        } else {
            for (RefreshFailureHandler f : failureCallbacks) f.onFailure(failureReason);
        }
    }

    // --- Parsing helpers ---------------------------------------------------------------

    /** Picks the highest-resolution VideoFile entry and returns its best download URL. */
    private String bestFileUrl(JSONArray files) {
        if (files == null || files.length() == 0) return null;

        JSONObject best = null;
        int bestResolution = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            int resolution = -1;
            JSONObject resolutionObj = file.optJSONObject("resolution");
            if (resolutionObj != null) {
                resolution = resolutionObj.optInt("id", -1);
            }
            if (resolution > bestResolution) {
                bestResolution = resolution;
                best = file;
            }
        }
        if (best == null) return null;

        String downloadUrl = best.optString("fileDownloadUrl", null);
        if (downloadUrl != null && !downloadUrl.isEmpty()) return downloadUrl;
        String fileUrl = best.optString("fileUrl", null);
        return (fileUrl != null && !fileUrl.isEmpty()) ? fileUrl : null;
    }

    private PtChannel parseChannel(JSONObject obj) {
        PtChannel channel = new PtChannel();
        channel.name = obj.optString("name", "");
        channel.displayName = obj.optString("displayName", channel.name);
        channel.description = obj.optString("description", "");
        channel.avatarUrl = resolveImageUrl(obj.optJSONArray("avatars"));
        return channel;
    }

    private PtVideo parseVideo(JSONObject obj) {
        PtVideo video = new PtVideo();
        video.id = String.valueOf(obj.optLong("id", -1));
        video.name = obj.optString("name", "");
        video.shortUUID = obj.optString("shortUUID", "");
        video.durationSeconds = obj.optLong("duration", -1);
        video.description = obj.optString("truncatedDescription", "");
        video.publishedAt = obj.optString("publishedAt", "");
        video.thumbnailUrl = resolveImageUrl(obj.optJSONArray("thumbnails"));

        if (video.thumbnailUrl.isEmpty()) {
            // Older servers (< 8.1): fall back to the deprecated relative path field.
            String legacyPath = obj.optString("thumbnailPath", null);
            if (legacyPath != null && !legacyPath.isEmpty()) {
                video.thumbnailUrl = Config.PEERTUBE_INSTANCE_URL + legacyPath;
            }
        }

        video.pageUrl = !video.shortUUID.isEmpty()
                ? Config.PEERTUBE_INSTANCE_URL + "/w/" + video.shortUUID
                : "";

        return video;
    }

    /**
     * Resolves an absolute URL from a PeerTube "images" array (thumbnails/avatars), which
     * on modern servers contains {@code fileUrl} (absolute) and on older servers only the
     * deprecated {@code path} (relative to the instance root).
     */
    private String resolveImageUrl(JSONArray images) {
        if (images == null || images.length() == 0) return "";
        JSONObject first = images.optJSONObject(0);
        if (first == null) return "";

        String fileUrl = first.optString("fileUrl", null);
        if (fileUrl != null && !fileUrl.isEmpty()) return fileUrl;

        String path = first.optString("path", null);
        if (path != null && !path.isEmpty()) return Config.PEERTUBE_INSTANCE_URL + path;

        return "";
    }

    /**
     * The OpenAPI spec for GET /users/me documents (likely erroneously) an array response;
     * in practice the endpoint returns a single object. This tolerates both.
     */
    private JSONObject firstObjectOrSelf(String body) throws Exception {
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            if (arr.length() == 0) throw new IllegalStateException("Réponse vide de /users/me");
            return arr.getJSONObject(0);
        }
        return new JSONObject(trimmed);
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9À-ÿ _-]", "").trim();
        return sanitized.isEmpty() ? "video" : sanitized;
    }
}
