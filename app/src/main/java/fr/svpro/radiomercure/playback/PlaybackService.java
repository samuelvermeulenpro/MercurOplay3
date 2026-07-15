package fr.svpro.radiomercure.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

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

/**
 * Background playback service shared by both the live radio stream and podcast/video
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
 */
@UnstableApi
public class PlaybackService extends MediaSessionService {

    public static final String COMMAND_SET_ARTWORK = "fr.svpro.radiomercure.SET_ARTWORK";
    public static final String EXTRA_ARTWORK_URL = "artwork_url";

    private MediaSession mediaSession;

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
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
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

        @NonNull
        @Override
        public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session,
                                                         @NonNull MediaSession.ControllerInfo controller) {
            MediaSession.ConnectionResult defaultResult = MediaSession.Callback.super.onConnect(session, controller);
            SessionCommands sessionCommands = defaultResult.availableSessionCommands.buildUpon()
                    .add(new SessionCommand(COMMAND_SET_ARTWORK, Bundle.EMPTY))
                    .build();
            return MediaSession.ConnectionResult.accept(sessionCommands, defaultResult.availablePlayerCommands);
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
