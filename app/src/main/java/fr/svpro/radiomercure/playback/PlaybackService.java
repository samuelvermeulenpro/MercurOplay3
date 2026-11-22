package fr.svpro.radiomercure.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import fr.svpro.radiomercure.MainActivity;
import fr.svpro.radiomercure.util.Config;
import fr.svpro.radiomercure.util.PlaybackPositionStore;

/**
 * Background playback service shared by both the live radio stream and PeerTube video
 * playback. Keeps a single ExoPlayer + MediaSession alive so playback continues with
 * system notification controls when the app is backgrounded.
 *
 * <p>Live "now playing" artwork (looked up externally from ICY metadata by
 * {@code LiveFragment}) is applied here, on the {@link Player} instance directly, via a
 * custom session command. Media3's {@code Player#replaceMediaItem} only reliably keeps
 * the session/notification metadata flowing when called on the player itself; calling it
 * through a {@code MediaController} (i.e. from the fragment) is a known source of stuck
 * metadata (see androidx/media issue #706) - that was the root cause of the "now playing"
 * display freezing after the first artwork update.
 * <p>Also periodically saves the playback position of whatever PeerTube
 * video is currently loaded (never the live stream) via {@link PlaybackPositionStore}, so
 * it can resume where it left off later - see {@link #savePositionIfApplicable()}. This
 * lives here rather than in the player Activities specifically so it keeps working while
 * backgrounded: those Activities detach their local session connection in onStop() (to
 * support audio-only background playback), so periodic saving tied to their lifecycle
 * would stop tracking the moment the screen turns off, which is exactly when background
 * listening matters most.
 */
@UnstableApi
public class PlaybackService extends MediaSessionService {

    public static final String COMMAND_SET_ARTWORK = "fr.svpro.radiomercure.SET_ARTWORK";
    public static final String EXTRA_ARTWORK_URL = "artwork_url";

    private static final long POSITION_SAVE_INTERVAL_MS = 5_000L;

    private MediaSession mediaSession;
    private final Handler positionSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable positionSaveTick = this::savePositionTickAndReschedule;

    @Override
    public void onCreate() {
        super.onCreate();

        ExoPlayer player = new ExoPlayer.Builder(this).build();

        Intent sessionIntent = new Intent(this, MainActivity.class);
        PendingIntent sessionActivityPendingIntent = PendingIntent.getActivity(
                this, 0, sessionIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivityPendingIntent)
                .setBitmapLoader(new DataSourceBitmapLoader(this))
                .setCallback(new ArtworkSessionCallback())
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState != Player.STATE_ENDED) return;
                // Finished naturally: clear any saved position rather than leaving one
                // pointing at (or near) the very end of the video.
                MediaItem item = player.getCurrentMediaItem();
                if (item != null && item.mediaId != null && !item.mediaId.isEmpty()) {
                    PlaybackPositionStore.clearPosition(PlaybackService.this, item.mediaId);
                }
            }
        });

        positionSaveHandler.postDelayed(positionSaveTick, POSITION_SAVE_INTERVAL_MS);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        positionSaveHandler.removeCallbacks(positionSaveTick);
        savePositionIfApplicable(); // final flush so the last few seconds aren't lost
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private void savePositionTickAndReschedule() {
        savePositionIfApplicable();
        positionSaveHandler.postDelayed(positionSaveTick, POSITION_SAVE_INTERVAL_MS);
    }

    /**
     * Saves the current position of whatever's loaded, unless it's the live stream or
     * nothing is actively playing right now.
     *
     * <p>Deliberately checks the actual URI against {@link Config#LIVE_STREAM_URL} rather
     * than {@link Player#isCurrentMediaItemLive()}: that flag is ExoPlayer's own heuristic
     * and can be true for on-demand content too, specifically when its duration
     * can't be determined (e.g. an MP3 with no Xing/VBRI header and no usable
     * Content-Length) - which would otherwise silently skip saving a real, resumable
     * position for that content.
     */
    private void savePositionIfApplicable() {
        if (mediaSession == null) return;
        Player player = mediaSession.getPlayer();
        if (!player.isPlaying()) return;

        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem == null || currentItem.mediaId == null || currentItem.mediaId.isEmpty()) return;
        if (currentItem.localConfiguration != null
                && Config.LIVE_STREAM_URL.equals(currentItem.localConfiguration.uri.toString())) {
            return;
        }

        PlaybackPositionStore.savePosition(
                this, currentItem.mediaId, player.getCurrentPosition(), player.getDuration());
    }

    /**
     * Sets (or clears) the artwork URI on the currently playing item's metadata, on the
     * player thread, without disturbing playback or the ICY dynamic metadata that keeps
     * flowing on top of it. Only the artwork field is touched - the static title/artist
     * are deliberately left untouched (they stay unset) so future ICY track-title updates
     * keep merging in normally on every call.
     */
    private void applyArtwork(@Nullable String artworkUrl) {
        Player player = mediaSession.getPlayer();
        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem == null) return;

        Uri artworkUri = (artworkUrl != null && !artworkUrl.isEmpty()) ? Uri.parse(artworkUrl) : null;

        MediaMetadata updatedMetadata = currentItem.mediaMetadata.buildUpon()
                .setArtworkUri(artworkUri)
                .build();
        MediaItem updatedItem = currentItem.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build();

        player.replaceMediaItem(player.getCurrentMediaItemIndex(), updatedItem);
    }

    private class ArtworkSessionCallback implements MediaSession.Callback {

        /**
         * Every controller gets all default session/player commands (same as Media3's own
         * default up to 1.10) plus the custom artwork command. Media3 1.11 deprecated the
         * synchronous onConnect(): its default now returns an empty "not implemented"
         * placeholder, so building on super.onConnect() left the app's own screens without
         * play/pause - nothing would play at all.
         */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.ConnectionResult> onConnectAsync(
                @NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
            SessionCommands sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(new SessionCommand(COMMAND_SET_ARTWORK, Bundle.EMPTY))
                    .build();
            return Futures.immediateFuture(new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build());
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session,
                                                               @NonNull MediaSession.ControllerInfo controller,
                                                               @NonNull SessionCommand customCommand,
                                                               @NonNull Bundle args) {
            if (COMMAND_SET_ARTWORK.equals(customCommand.customAction)) {
                applyArtwork(args.getString(EXTRA_ARTWORK_URL));
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }
    }
}