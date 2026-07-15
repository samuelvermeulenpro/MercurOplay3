package fr.svpro.radiomercure.live;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import fr.svpro.radiomercure.util.Config;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Looks up cover artwork for "artist - title" using the free, key-less
 * iTunes Search API and returns a high resolution (600x600) artwork URL.
 */
public class CoverArtFetcher {

    private static final String TAG = "CoverArtFetcher";

    public interface Callback {
        void onArtworkFound(String artworkUrl);
        void onNotFound();
    }

    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CoverArtFetcher(OkHttpClient client) {
        this.client = client;
    }

    public void fetch(String artist, String title, Callback callback) {
        String term = (artist == null ? "" : artist + " ") + (title == null ? "" : title);
        term = term.trim();
        if (term.isEmpty()) {
            callback.onNotFound();
            return;
        }

        String url;
        try {
            String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name());
            url = Config.ITUNES_SEARCH_URL + "?term=" + encoded + "&media=music&limit=1";
        } catch (Exception e) {
            callback.onNotFound();
            return;
        }

        Request request = new Request.Builder().url(url).get().build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "iTunes lookup failed: " + e.getMessage());
                mainHandler.post(callback::onNotFound);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        mainHandler.post(callback::onNotFound);
                        return;
                    }
                    String body = r.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray results = json.optJSONArray("results");
                    if (results == null || results.length() == 0) {
                        mainHandler.post(callback::onNotFound);
                        return;
                    }
                    JSONObject first = results.getJSONObject(0);
                    String artworkUrl100 = first.optString("artworkUrl100", null);
                    if (artworkUrl100 == null) {
                        mainHandler.post(callback::onNotFound);
                        return;
                    }
                    String hiRes = artworkUrl100.replace("100x100bb", "600x600bb");
                    mainHandler.post(() -> callback.onArtworkFound(hiRes));
                } catch (Exception e) {
                    Log.w(TAG, "iTunes parse failed: " + e.getMessage());
                    mainHandler.post(callback::onNotFound);
                }
            }
        });
    }
}
