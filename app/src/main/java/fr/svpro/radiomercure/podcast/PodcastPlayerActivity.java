package fr.svpro.radiomercure.podcast;

import android.content.ComponentName;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.playback.DurationOverridePlayer;
import fr.svpro.radiomercure.playback.PlaybackService;
import fr.svpro.radiomercure.util.PlaybackPositionStore;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Note on cover art: {@code MediaMetadata.setArtworkUri(...)} (a remote URL) is not
 * reliably loaded by {@link PlayerView}'s own built-in artwork view - it works fine for
 * the system notification (a different bitmap-loading path), but not here. Using
 * {@code setArtworkData(byte[], pictureType)} (the actual image bytes) instead is the
 * reliable path, since PlayerView renders it directly rather than trying to fetch a URI
 * itself - and unlike an overlay ImageView drawn on top, it's correctly layered by
 * PlayerView *behind* its own playback controls rather than covering them. That means the
 * artwork bytes need to be fetched before playback starts (see {@link #playEpisode()}),
 * rather than set as a URI upfront.
 */
@UnstableApi
public class PodcastPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_EPISODE = "extra_episode";

    private PlayerView playerView;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private Episode episode;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_podcast_player);

        episode = (Episode) getIntent().getSerializableExtra(EXTRA_EPISODE);

        playerView = findViewById(R.id.playerView);
        TextView textTitle = findViewById(R.id.textPlayerTitle);
        TextView textDate = findViewById(R.id.textPlayerDate);
        TextView textDescription = findViewById(R.id.textPlayerDescription);
        ImageButton buttonClose = findViewById(R.id.buttonClose);

        buttonClose.setOnClickListener(v -> finish());

        if (episode != null) {
            textTitle.setText(episode.title);
            textDate.setText(episode.pubDate);
            textDescription.setText(episode.description);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                setVideoTrackDisabled(false); // rendering video again now that we're visible

                // If ExoPlayer can't determine the real duration itself (common for MP3s
                // served without a Content-Length or Xing/VBRI header), fall back to what
                // the RSS feed declared so the seek bar isn't stuck blank at 00:00.
                long feedDurationMs = (episode != null && episode.durationSeconds > 0)
                        ? episode.durationSeconds * 1000L
                        : 0L;
                Player playerForView = feedDurationMs > 0
                        ? new DurationOverridePlayer(mediaController, feedDurationMs)
                        : mediaController;
                playerView.setPlayer(playerForView);

                playEpisode();
            } catch (Exception ignored) {
                // Playback controls simply won't be available; nothing else to recover here.
            }
        }, MoreExecutors.directExecutor());
    }

    private void playEpisode() {
        if (mediaController == null || episode == null || episode.mediaUrl.isEmpty()) return;

        MediaItem currentItem = mediaController.getCurrentMediaItem();
        boolean alreadyPlayingThis = currentItem != null
                && currentItem.localConfiguration != null
                && episode.mediaUrl.equals(currentItem.localConfiguration.uri.toString());
        if (alreadyPlayingThis) return;

        if (episode.thumbnailUrl.isEmpty()) {
            startPlaybackWithArtwork(null);
        } else {
            httpClient.newCall(new Request.Builder().url(episode.thumbnailUrl).build())
                    .enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            runOnUiThread(() -> startPlaybackWithArtwork(null));
                        }

                        @Override
                        public void onResponse(Call call, Response response) {
                            byte[] bytes = null;
                            try (Response r = response) {
                                if (r.isSuccessful() && r.body() != null) {
                                    bytes = r.body().bytes();
                                }
                            } catch (IOException ignored) {
                                // Falls through with bytes == null, i.e. no artwork.
                            }
                            byte[] finalBytes = bytes;
                            runOnUiThread(() -> startPlaybackWithArtwork(finalBytes));
                        }
                    });
        }
    }

    /**
     * Actually starts playback of {@link #episode}, with the given artwork bytes (if any)
     * attached to the MediaItem's metadata. Re-checks that nothing else already changed
     * what's loaded in the meantime (the artwork fetch above is asynchronous).
     */
    private void startPlaybackWithArtwork(@Nullable byte[] artworkBytes) {
        if (mediaController == null || episode == null) return;

        MediaItem currentItem = mediaController.getCurrentMediaItem();
        boolean alreadyPlayingThis = currentItem != null
                && currentItem.localConfiguration != null
                && episode.mediaUrl.equals(currentItem.localConfiguration.uri.toString());
        if (alreadyPlayingThis) return;

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setTitle(episode.title)
                .setArtist(getString(R.string.app_name));
        if (artworkBytes != null) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(episode.mediaUrl)
                .setMediaId(episode.mediaUrl)
                .setMediaMetadata(metadataBuilder.build())
                .build();
        mediaController.setMediaItem(mediaItem);
        mediaController.prepare();

        long savedPosition = PlaybackPositionStore.getSavedPosition(this, episode.mediaUrl);
        if (savedPosition > 0) {
            mediaController.seekTo(savedPosition);
        }

        mediaController.play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Most episodes here are functionally an audio track with a static poster over it
        // (per the person's own content), so there's no reason to pause just because the
        // screen turned off or the app got backgrounded - same expectation as AntennaPod or
        // NewPipe's background-audio behavior. Instead of pausing, the video track is
        // explicitly disabled while not visible (no point decoding frames nobody can see -
        // saves battery/bandwidth) and re-enabled in onStart() once the player is visible
        // again. Playback itself never stops; only this Activity's local UI/session
        // connection is detached, exactly like the live stream already does.
        setVideoTrackDisabled(true);
        playerView.setPlayer(null);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        mediaController = null;
    }

    /**
     * Enables/disables video track selection on the shared player. This is a property of
     * the player itself (not this particular controller), so it persists on the underlying
     * ExoPlayer in {@link PlaybackService} across reconnects - disabling it just before this
     * controller is released still leaves playback running audio-only in the background.
     */
    private void setVideoTrackDisabled(boolean disabled) {
        if (mediaController == null) return;
        TrackSelectionParameters params = mediaController.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
                .build();
        mediaController.setTrackSelectionParameters(params);
    }
}