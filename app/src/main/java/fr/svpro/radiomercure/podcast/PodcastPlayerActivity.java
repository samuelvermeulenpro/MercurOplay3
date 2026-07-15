package fr.svpro.radiomercure.podcast;

import android.content.ComponentName;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.playback.PlaybackService;

@UnstableApi
public class PodcastPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_EPISODE = "extra_episode";

    private PlayerView playerView;
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private Episode episode;

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
                playerView.setPlayer(mediaController);
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

        if (!alreadyPlayingThis) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(episode.mediaUrl)
                    .setMediaId(episode.mediaUrl)
                    .build();
            mediaController.setMediaItem(mediaItem);
            mediaController.prepare();
            mediaController.play();
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
    }
}
