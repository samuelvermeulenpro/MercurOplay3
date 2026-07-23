package fr.svpro.radiomercure.peertube;

import android.content.ComponentName;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.playback.PlaybackService;
import okhttp3.OkHttpClient;

/**
 * PeerTube video player activity: resolves the best playback source (HLS master
 * playlist if available, otherwise the highest-resolution progressive file) before
 * playing via the shared PlaybackService - mirrors PodcastPlayerActivity, except the
 * playable URI isn't known upfront and must be resolved from the video's details.
 */
@UnstableApi
public class PeerTubePlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "extra_pt_video";

    private PlayerView playerView;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private PeerTubeApiClient apiClient;
    private PtVideo video;

    private String resolvedPlaybackUri;
    private boolean resolutionRequested = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_podcast_player);

        video = (PtVideo) getIntent().getSerializableExtra(EXTRA_VIDEO);
        apiClient = new PeerTubeApiClient(this, new OkHttpClient());

        playerView = findViewById(R.id.playerView);
        TextView textTitle = findViewById(R.id.textPlayerTitle);
        TextView textDate = findViewById(R.id.textPlayerDate);
        TextView textDescription = findViewById(R.id.textPlayerDescription);
        ImageButton buttonClose = findViewById(R.id.buttonClose);

        buttonClose.setOnClickListener(v -> finish());

        if (video != null) {
            textTitle.setText(video.name);
            textDate.setText(formatDate(video.publishedAt));
            textDescription.setText(video.description);
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
                playerView.setPlayer(mediaController);
                resolveAndPlay();
            } catch (Exception ignored) {
                // Playback controls simply won't be available; nothing else to recover here.
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Unlike podcast episodes (whose direct media URL is already known from the RSS
     * feed), a PeerTube video's playable source (HLS playlist or progressive file) is
     * only known after fetching its full details - resolved once per activity instance.
     */
    private void resolveAndPlay() {
        if (video == null || video.id.isEmpty() || resolutionRequested) return;
        resolutionRequested = true;

        if (resolvedPlaybackUri != null) {
            playResolved();
            return;
        }

        Toast.makeText(this, R.string.peertube_resolving_playback, Toast.LENGTH_SHORT).show();
        apiClient.fetchPlaybackSource(video.id, new PeerTubeApiClient.PlaybackSourceCallback() {
            @Override
            public void onSuccess(String playbackUri) {
                resolvedPlaybackUri = playbackUri;
                playResolved();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(PeerTubePlayerActivity.this, R.string.peertube_playback_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void playResolved() {
        if (mediaController == null || resolvedPlaybackUri == null) return;

        MediaItem currentItem = mediaController.getCurrentMediaItem();
        boolean alreadyPlayingThis = currentItem != null
                && currentItem.localConfiguration != null
                && resolvedPlaybackUri.equals(currentItem.localConfiguration.uri.toString());

        if (!alreadyPlayingThis) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(resolvedPlaybackUri)
                    .setMediaId(resolvedPlaybackUri)
                    .build();
            mediaController.setMediaItem(mediaItem);
            mediaController.prepare();
            mediaController.play();
        }
    }

    private String formatDate(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            Date date = isoFormat.parse(isoDate);
            return date != null ? DateFormat.getLongDateFormat(this).format(date) : isoDate;
        } catch (ParseException e) {
            return isoDate;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mediaController != null) {
            mediaController.pause();
        }
        playerView.setPlayer(null);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        mediaController = null;
        resolutionRequested = false;
    }
}
