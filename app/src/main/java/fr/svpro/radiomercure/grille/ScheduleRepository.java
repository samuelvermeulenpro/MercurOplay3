package fr.svpro.radiomercure.grille;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.svpro.radiomercure.util.Config;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads the programme schedule from the Nextcloud calendar (iCalendar export), the same
 * source as the website's grid. Parsed events are kept in memory for 15 minutes; the last
 * valid download is also saved to disk, so the grid still shows (flagged as stale) when
 * Nextcloud can't be reached or the device is offline.
 */
public final class ScheduleRepository {

    private static final String TAG = "ScheduleRepository";
    private static final long TTL_MS = 15 * 60 * 1000L;
    private static final String CACHE_FILE = "grille.ics";

    /** Station time zone: floating calendar times and day boundaries, same as the website. */
    public static final ZoneId STATION_ZONE = ZoneId.of("Europe/Paris");

    public interface Callback {
        /** {@code stale} is true when this is the saved copy because the download failed. */
        void onLoaded(List<IcsEvent> events, boolean stale);

        void onError(String message);
    }

    private static volatile ScheduleRepository instance;

    private final OkHttpClient client = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File cacheFile;

    private List<IcsEvent> events;
    private long fetchedAt;

    private ScheduleRepository(Context context) {
        cacheFile = new File(context.getApplicationContext().getFilesDir(), CACHE_FILE);
    }

    public static ScheduleRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (ScheduleRepository.class) {
                if (instance == null) instance = new ScheduleRepository(context);
            }
        }
        return instance;
    }

    public static boolean isConfigured() {
        return !Config.GRILLE_CALENDAR_URL.trim().isEmpty();
    }

    /**
     * Accepts what the theme accepts: a public Nextcloud share link
     * (…/apps/calendar/p/TOKEN), a webcal:// link, or a private CalDAV calendar URL
     * (…/remote.php/dav/calendars/USER/rivendell) - all turned into the ICS export URL.
     */
    static String exportUrl(String url) {
        url = url.trim();
        if (url.isEmpty()) return "";
        url = url.replaceFirst("(?i)^webcals?://", "https://");
        Matcher m = Pattern.compile("^(https?://.+?)/(?:index\\.php/)?apps/calendar/(?:embed/)?p/([A-Za-z0-9]+)")
                .matcher(url);
        if (m.find()) {
            url = m.group(1) + "/remote.php/dav/public-calendars/" + m.group(2);
        }
        if (url.contains("/remote.php/dav/") && !url.contains("export")) {
            url = url.replaceAll("/+$", "") + "?export";
        }
        return url;
    }

    public void load(boolean force, @NonNull Callback callback) {
        if (!isConfigured()) {
            callback.onLoaded(Collections.emptyList(), false);
            return;
        }
        synchronized (this) {
            if (!force && events != null && System.currentTimeMillis() - fetchedAt < TTL_MS) {
                callback.onLoaded(events, false);
                return;
            }
        }

        Request.Builder builder = new Request.Builder()
                .url(exportUrl(Config.GRILLE_CALENDAR_URL))
                .header("Accept", "text/calendar")
                .get();
        if (!Config.GRILLE_CALENDAR_USER.isEmpty() && !Config.GRILLE_CALENDAR_PASSWORD.isEmpty()) {
            String credentials = Config.GRILLE_CALENDAR_USER + ":" + Config.GRILLE_CALENDAR_PASSWORD;
            builder.header("Authorization", "Basic " + Base64.encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }

        client.newCall(builder.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.d(TAG, "calendar unavailable: " + e.getMessage());
                fallback(e.getMessage(), callback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        fallback("HTTP " + r.code(), callback);
                        return;
                    }
                    if (!body.contains("BEGIN:VCALENDAR")) {
                        fallback("Réponse invalide", callback);
                        return;
                    }
                    List<IcsEvent> parsed = IcsParser.parse(body, STATION_ZONE);
                    synchronized (ScheduleRepository.this) {
                        events = parsed;
                        fetchedAt = System.currentTimeMillis();
                    }
                    saveCache(body);
                    mainHandler.post(() -> callback.onLoaded(parsed, false));
                } catch (IOException e) {
                    fallback(e.getMessage(), callback);
                }
            }
        });
    }

    /** Download failed: serve the in-memory copy, else the saved one, else report the error. */
    private void fallback(String message, Callback callback) {
        List<IcsEvent> last;
        synchronized (this) {
            last = events;
        }
        if (last == null) {
            String saved = readCache();
            if (saved != null) {
                last = IcsParser.parse(saved, STATION_ZONE);
                synchronized (this) {
                    events = last;
                    fetchedAt = 0; // stale: retried on next load.
                }
            }
        }
        List<IcsEvent> result = last;
        mainHandler.post(() -> {
            if (result != null) {
                callback.onLoaded(result, true);
            } else {
                callback.onError(message);
            }
        });
    }

    private void saveCache(String body) {
        try (OutputStream out = new FileOutputStream(cacheFile)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "could not save calendar cache", e);
        }
    }

    private String readCache() {
        if (!cacheFile.exists()) return null;
        try (InputStream in = new FileInputStream(cacheFile)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }
}
