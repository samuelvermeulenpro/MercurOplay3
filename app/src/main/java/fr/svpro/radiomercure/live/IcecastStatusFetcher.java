package fr.svpro.radiomercure.live;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import fr.svpro.radiomercure.util.Config;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Periodically polls the Icecast JSON status endpoint to read the current
 * number of listeners on the live mount. Icecast servers expose one or more
 * "source" entries under icestats; we sum listeners across all sources found,
 * or match the specific mount when its listenurl is present.
 */
public class IcecastStatusFetcher {

    private static final String TAG = "IcecastStatusFetcher";

    public interface Listener {
        void onListenerCount(int count);
        void onUnavailable();
    }

    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::poll;
    private Listener listener;
    private boolean running = false;

    public IcecastStatusFetcher(OkHttpClient client) {
        this.client = client;
    }

    public void start(Listener listener) {
        this.listener = listener;
        running = true;
        poll();
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(pollRunnable);
    }

    private void poll() {
        if (!running) return;

        Request request = new Request.Builder().url(Config.LIVE_STATUS_URL).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "status-json unavailable: " + e.getMessage());
                notifyUnavailable();
                scheduleNext();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        notifyUnavailable();
                        return;
                    }
                    String body = r.body().string();
                    int count = parseListenerCount(body);
                    if (count >= 0) {
                        notifyCount(count);
                    } else {
                        notifyUnavailable();
                    }
                } catch (Exception e) {
                    Log.d(TAG, "status-json parse failed: " + e.getMessage());
                    notifyUnavailable();
                } finally {
                    scheduleNext();
                }
            }
        });
    }

    private int parseListenerCount(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject icestats = json.optJSONObject("icestats");
            if (icestats == null) return -1;

            Object source = icestats.opt("source");
            if (source instanceof JSONArray) {
                JSONArray arr = (JSONArray) source;
                int total = 0;
                boolean any = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s != null && s.has("listeners")) {
                        total += s.optInt("listeners", 0);
                        any = true;
                    }
                }
                return any ? total : -1;
            } else if (source instanceof JSONObject) {
                JSONObject s = (JSONObject) source;
                return s.has("listeners") ? s.optInt("listeners", -1) : -1;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void notifyCount(int count) {
        mainHandler.post(() -> {
            if (running && listener != null) listener.onListenerCount(count);
        });
    }

    private void notifyUnavailable() {
        mainHandler.post(() -> {
            if (running && listener != null) listener.onUnavailable();
        });
    }

    private void scheduleNext() {
        if (running) {
            mainHandler.postDelayed(pollRunnable, Config.LISTENER_POLL_INTERVAL_MS);
        }
    }
}
