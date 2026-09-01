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
    private boolean connecting = false;
    // Set when the person taps play while a passive (view-creation-time) connection
    // attempt is still in flight, so the pending tap gets honored once it completes.
    private boolean playRequestedWhileConnecting = false;

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

        // Neutral resting state by default (placeholder cover, no metadata sync, nothing
        // fetched). If a stream is already playing in the background (service survived a
        // tab switch / the fragment being recreated), connect(false) below replaces this
        // with the real current state within a moment - it never forces playback to start
        // on its own, only observes what's already happening.
        showPlaceholderNowPlaying();
        buttonPlayPause.setImageResource(R.drawable.ic_play);
        buttonPlayPause.setContentDescription(getString(R.string.live_play));
        progressBuffering.setVisibility(View.GONE);
        textError.setVisibility(View.GONE);
        buttonRetry.setVisibility(View.GONE);

        buttonPlayPause.setOnClickListener(v -> togglePlayback());
        buttonRetry.setOnClickListener(v -> {
            textError.setVisibility(View.GONE);
            buttonRetry.setVisibility(View.GONE);
            connect(true);
        });

        connect(false);
    }

    /**
     * Resets the "now playing" area to its neutral state (placeholder cover, default
     * station name, no artist) without touching the player/service - used before any
     * playback has been requested, and if artwork lookup comes back empty.
     */
    private void showPlaceholderNowPlaying() {
        imageCover.setImageResource(R.drawable.ic_placeholder_cover);
        textTrackTitle.setText(R.string.live_no_track);
        textTrackArtist.setText("");
    }

    private void togglePlayback() {
        if (mediaController != null && mediaController.isPlaying()) {
            mediaController.pause();
            return;
        }
        // Not currently playing (or not connected yet): connect if needed, then start/resume.
        connect(true);
    }

    /**
     * Connects to the shared {@link PlaybackService} if not already connected, then either:
     * <ul>
     *   <li>{@code startPlayback=false} (called passively on view creation): only syncs the
     *   UI to whatever the player is really doing right now - never starts playback on its
     *   own. This is what fixes the play button/cover resetting to placeholder after
     *   switching tabs or backgrounding the app while the stream keeps playing: the fragment
     *   is recreated with no controller, and without this call there is nothing to make
     *   {@code Player.Listener} fire again for a state that isn't changing.</li>
     *   <li>{@code startPlayback=true} (an explicit play tap): prepares the stream if needed
     *   and plays/resumes it once connected.</li>
     * </ul>
     */
    private void connect(boolean startPlayback) {
        if (mediaController != null) {
            if (startPlayback) {
                if (mediaController.getPlaybackState() == Player.STATE_IDLE) {
                    prepareLiveStreamIfNeeded();
                }
                mediaController.play();
            } else {
                syncUiToCurrentPlayerState();
            }
            return;
        }

        if (connecting) {
            if (startPlayback) playRequestedWhileConnecting = true;
            return;
        }

        connecting = true;
        if (startPlayback) {
            progressBuffering.setVisibility(View.VISIBLE);
            buttonPlayPause.setVisibility(View.INVISIBLE);
        }

        SessionToken sessionToken = new SessionToken(
                requireContext(),
                new android.content.ComponentName(requireContext(), PlaybackService.class));
        controllerFuture = new MediaController.Builder(requireContext(), sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            connecting = false;
            boolean shouldPlay = startPlayback || playRequestedWhileConnecting;
            playRequestedWhileConnecting = false;
            try {
                mediaController = controllerFuture.get();
                setupPlayerListener();
                if (shouldPlay) {
                    prepareLiveStreamIfNeeded();
                    mediaController.play();
                } else {
                    syncUiToCurrentPlayerState();
                }
            } catch (Exception e) {
                if (shouldPlay) {
                    showError();
                }
                // A passive connection attempt failing silently keeps the placeholder state -
                // no error worth interrupting the person over just from opening the tab.
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

        updatePlayPauseIcon();
    }

    /**
     * Reads the player's actual current state right after connecting and reflects it
     * immediately, rather than waiting for a {@code Player.Listener} callback that will
     * never fire if nothing is actually changing (e.g. reconnecting to a stream that was
     * already happily playing before the fragment got recreated).
     */
    private void syncUiToCurrentPlayerState() {
        if (mediaController == null) return;

        int state = mediaController.getPlaybackState();
        boolean isBuffering = state == Player.STATE_BUFFERING;
        progressBuffering.setVisibility(isBuffering ? View.VISIBLE : View.GONE);
        buttonPlayPause.setVisibility(isBuffering ? View.INVISIBLE : View.VISIBLE);

        updatePlayPauseIcon();

        if (state == Player.STATE_IDLE) {
            showPlaceholderNowPlaying();
        } else {
            applyNowPlaying(mediaController.getMediaMetadata());
        }
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
            showPlaceholderNowPlaying();
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
        connecting = false;
        playRequestedWhileConnecting = false;
    }
}
