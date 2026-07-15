package fr.svpro.radiomercure.podcast;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.svpro.radiomercure.util.Config;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PodcastRepository {

    public interface FeedCallback {
        void onSuccess(List<Episode> episodes);
        void onError(String message);
    }

    private final OkHttpClient client;
    private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PodcastRepository(OkHttpClient client) {
        this.client = client;
    }

    public void fetchFeed(FeedCallback callback) {
        Request request = new Request.Builder().url(Config.PODCAST_FEED_URL).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response; ResponseBody body = r.body()) {
                    if (!r.isSuccessful() || body == null) {
                        mainHandler.post(() -> callback.onError("HTTP " + r.code()));
                        return;
                    }
                    byte[] bytes = body.bytes();
                    parseExecutor.execute(() -> {
                        try {
                            List<Episode> episodes = new MrssFeedParser()
                                    .parse(new java.io.ByteArrayInputStream(bytes));
                            mainHandler.post(() -> callback.onSuccess(episodes));
                        } catch (Exception e) {
                            mainHandler.post(() -> callback.onError(e.getMessage()));
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            }
        });
    }
}
