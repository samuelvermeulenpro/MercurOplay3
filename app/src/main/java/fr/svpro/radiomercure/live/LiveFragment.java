package fr.svpro.radiomercure.live;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.playback.PlaybackService;
import fr.svpro.radiomercure.util.Config;
import okhttp3.OkHttpClient;

@UnstableApi
public class LiveFragment extends Fragment {

    private ImageView imageCover;
    private TextView textTrackTitle;
    private TextView textTrackArtist;
    private TextView textListeners;
    private TextView textError;
    private ImageButton buttonPlayPause;
    private ProgressBar progressBuffering;
    private Button buttonRetry;

    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;

    private OkHttpClient httpClient;
    private CoverArtFetcher coverArtFetcher;
    private IcecastStatusFetcher statusFetcher;

    private String lastArtworkQueryKey = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_live, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        imageCover = view.findViewById(R.id.imageCover);
        textTrackTitle = view.findViewById(R.id.textTrackTitle);
        textTrackArtist = view.findViewById(R.id.textTrackArtist);
        textListeners = view.findViewById(R.id.textListeners);
        textError = view.findViewById(R.id.textError);
        buttonPlayPause = view.findViewById(R.id.buttonPlayPause);
        progressBuffering = view.findViewById(R.id.progressBuffering);
        buttonRetry = view.findViewById(R.id.buttonRetry);

        httpClient = new OkHttpClient();
        coverArtFetcher = new CoverArtFetcher(httpClient);
        statusFetcher = new IcecastStatusFetcher(httpClient);

        buttonPlayPause.setOnClickListener(v -> togglePlayback());
        buttonRetry.setOnClickListener(v -> {
            textError.setVisibility(View.GONE);
            buttonRetry.setVisibility(View.GONE);
            if (mediaController != null) {
                mediaController.prepare();
                mediaController.play();
            }
        });

        connectToPlaybackService();
    }

    private void connectToPlaybackService() {
        SessionToken sessionToken = new SessionToken(
                requireContext(),
                new android.content.ComponentName(requireContext(), PlaybackService.class));
        controllerFuture = new MediaController.Builder(requireContext(), sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                setupPlayerListener();
                prepareLiveStreamIfNeeded();
            } catch (Exception e) {
                showError();
            }
        }, MoreExecutors.directExecutor());
    }

    private void prepareLiveStreamIfNeeded() {
        if (mediaController == null) return;
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        boolean alreadyOnLiveStream = currentItem != null
                && currentItem.localConfiguration != null
                && Config.LIVE_STREAM_URL.equals(currentItem.localConfiguration.uri.toString());

        if (!alreadyOnLiveStream) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Config.LIVE_STREAM_URL)
                    .setMediaId("live_stream")
                    .build();
            mediaController.setMediaItem(mediaItem);
            mediaController.prepare();
        }
        updatePlayPauseIcon();
    }

    private void setupPlayerListener() {
        if (mediaController == null) return;

        mediaController.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                progressBuffering.setVisibility(
                        playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                buttonPlayPause.setVisibility(
                        playbackState == Player.STATE_BUFFERING ? View.INVISIBLE : View.VISIBLE);
                if (playbackState == Player.STATE_READY) {
                    textError.setVisibility(View.GONE);
                    buttonRetry.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                showError();
            }

            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                applyNowPlaying(mediaMetadata);
            }
        });

        // Apply metadata already present (e.g. if playback started before this listener attached)
        applyNowPlaying(mediaController.getMediaMetadata());
        updatePlayPauseIcon();
    }

    private void applyNowPlaying(MediaMetadata metadata) {
        String rawTitle = metadata.title != null ? metadata.title.toString() : null;
        String artist = metadata.artist != null ? metadata.artist.toString() : null;

        String title = rawTitle;
        if (artist == null && rawTitle != null && rawTitle.contains(" - ")) {
            String[] parts = rawTitle.split(" - ", 2);
            artist = parts[0].trim();
            title = parts[1].trim();
        }

        if (title == null || title.trim().isEmpty()) {
            textTrackTitle.setText(R.string.live_no_track);
            textTrackArtist.setText("");
            return;
        }

        textTrackTitle.setText(title);
        textTrackArtist.setText(artist != null ? artist : "");

        String queryKey = (artist == null ? "" : artist) + "|" + title;
        if (!queryKey.equals(lastArtworkQueryKey)) {
            lastArtworkQueryKey = queryKey;
            fetchCoverArt(artist, title);
        }
    }

    private void fetchCoverArt(String artist, String title) {
        coverArtFetcher.fetch(artist, title, new CoverArtFetcher.Callback() {
            @Override
            public void onArtworkFound(String artworkUrl) {
                if (!isAdded()) return;
                Glide.with(LiveFragment.this)
                        .load(artworkUrl)
                        .apply(new RequestOptions()
                                .placeholder(R.drawable.ic_placeholder_cover)
                                .error(R.drawable.ic_placeholder_cover))
                        .into(imageCover);
                updateNotificationArtwork(artworkUrl);
            }

            @Override
            public void onNotFound() {
                if (!isAdded()) return;
                imageCover.setImageResource(R.drawable.ic_placeholder_cover);
                updateNotificationArtwork(null);
            }
        });
    }

    /**
     * Relays the artwork URL to {@link PlaybackService} via a custom session command so the
     * update happens directly on the player (see PlaybackService for why: calling
     * replaceMediaItem from a MediaController rather than the player itself is a known
     * source of stuck ICY metadata).
     */
    private void updateNotificationArtwork(@Nullable String artworkUrl) {
        if (mediaController == null) return;
        Bundle args = new Bundle();
        if (artworkUrl != null) {
            args.putString(PlaybackService.EXTRA_ARTWORK_URL, artworkUrl);
        }
        mediaController.sendCustomCommand(
                new SessionCommand(PlaybackService.COMMAND_SET_ARTWORK, Bundle.EMPTY), args);
    }

    private void togglePlayback() {
        if (mediaController == null) return;
        if (mediaController.isPlaying()) {
            mediaController.pause();
        } else {
            if (mediaController.getPlaybackState() == Player.STATE_IDLE) {
                mediaController.prepare();
            }
            mediaController.play();
        }
    }

    private void updatePlayPauseIcon() {
        if (mediaController == null) return;
        boolean playing = mediaController.isPlaying();
        buttonPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        buttonPlayPause.setContentDescription(getString(playing ? R.string.live_pause : R.string.live_play));
    }

    private void showError() {
        progressBuffering.setVisibility(View.GONE);
        buttonPlayPause.setVisibility(View.VISIBLE);
        textError.setVisibility(View.VISIBLE);
        buttonRetry.setVisibility(View.VISIBLE);
    }

    @Override
    public void onStart() {
        super.onStart();
        statusFetcher.start(new IcecastStatusFetcher.Listener() {
            @Override
            public void onListenerCount(int count) {
                if (isAdded()) {
                    textListeners.setText(getString(R.string.live_listeners_format, count));
                }
            }

            @Override
            public void onUnavailable() {
                if (isAdded()) {
                    textListeners.setText(R.string.live_listeners_unknown);
                }
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        statusFetcher.stop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        mediaController = null;
    }
}
