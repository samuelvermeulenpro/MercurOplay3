package fr.svpro.radiomercure.playback;

import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

/**
 * Wraps a {@link Player} to report a known duration (e.g. from an RSS feed's
 * {@code <itunes:duration>}) when the underlying player can't determine one itself -
 * typically a progressive audio stream served without a {@code Content-Length} header and
 * without an embedded Xing/VBRI header, which leaves ExoPlayer with no way to know the
 * total length on its own.
 *
 * <p>This only affects what's <i>displayed</i> by {@code PlayerView}'s built-in controls
 * (duration, progress fraction, whether the seek bar shows at all). It does not itself fix
 * broken seeking - dragging the seek bar still calls straight through to the real player's
 * {@code seekTo()}, so how well that actually lands depends on whether the server supports
 * HTTP Range requests. Without this wrapper the seek bar doesn't appear at all (stuck at
 * 00:00 total); with it, at least the known duration and progress show correctly, and
 * seeking gets a chance to work rather than never being offered in the first place.
 */
@UnstableApi
public class DurationOverridePlayer extends ForwardingPlayer {

    private final long overrideDurationMs;

    public DurationOverridePlayer(Player player, long overrideDurationMs) {
        super(player);
        this.overrideDurationMs = overrideDurationMs;
    }

    @Override
    public long getDuration() {
        long actual = super.getDuration();
        if (actual != C.TIME_UNSET && actual > 0) return actual;
        return overrideDurationMs > 0 ? overrideDurationMs : actual;
    }

    @Override
    public boolean isCurrentMediaItemSeekable() {
        return overrideDurationMs > 0 || super.isCurrentMediaItemSeekable();
    }
}
