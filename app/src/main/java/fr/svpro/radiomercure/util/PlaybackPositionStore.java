package fr.svpro.radiomercure.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers the last playback position for a given video (keyed by its stable
 * media URL or id) so playback can resume where it left off later, matching the behavior
 * of podcast apps like AntennaPod.
 *
 * <p>Positions under {@link #RESUME_THRESHOLD_MS} aren't worth resuming (barely started),
 * and positions within {@link #END_THRESHOLD_MS} of the known duration are treated as
 * "finished" - both cases clear any stored position rather than saving a value that would
 * produce an awkward resume point.
 */
public final class PlaybackPositionStore {

    private static final String PREFS_NAME = "playback_positions";
    private static final long RESUME_THRESHOLD_MS = 5_000L;
    private static final long END_THRESHOLD_MS = 15_000L;

    private PlaybackPositionStore() {
    }

    /**
     * Saves the current position for {@code key}, or clears it if the position is trivial
     * (just started) or effectively at the end (finished) - {@code durationMs} may be 0 or
     * negative if not yet known, in which case only the "just started" check applies.
     */
    public static void savePosition(Context context, String key, long positionMs, long durationMs) {
        if (key == null || key.isEmpty() || positionMs < 0) return;

        boolean justStarted = positionMs < RESUME_THRESHOLD_MS;
        boolean finished = durationMs > 0 && positionMs >= durationMs - END_THRESHOLD_MS;

        if (justStarted || finished) {
            clearPosition(context, key);
        } else {
            prefs(context).edit().putLong(key, positionMs).apply();
        }
    }

    /** Returns the saved position in ms, or 0 if none is stored for {@code key}. */
    public static long getSavedPosition(Context context, String key) {
        if (key == null || key.isEmpty()) return 0L;
        return prefs(context).getLong(key, 0L);
    }

    public static void clearPosition(Context context, String key) {
        if (key == null || key.isEmpty()) return;
        prefs(context).edit().remove(key).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}