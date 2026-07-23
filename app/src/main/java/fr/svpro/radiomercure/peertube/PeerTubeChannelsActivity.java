package fr.svpro.radiomercure.peertube;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import fr.svpro.radiomercure.R;
import okhttp3.OkHttpClient;

/**
 * Single screen with two states: the list of the authenticated user's PeerTube channels,
 * and (after tapping one) that channel's videos, each downloadable and shareable.
 * Sharing always uses the video's watch-page URL ({@code {instance}/w/{shortUUID}}), not
 * the raw media file URL.
 */
public class PeerTubeChannelsActivity extends AppCompatActivity {

    private enum Screen { CHANNELS, VIDEOS }

    private RecyclerView recyclerList;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private TextView textEmptyMessage;
    private TextView textHeaderTitle;
    private ImageButton buttonBack;

    private PtChannelAdapter channelAdapter;
    private PtVideoAdapter videoAdapter;
    private PeerTubeApiClient apiClient;

    private Screen currentScreen = Screen.CHANNELS;
    private PtChannel currentChannel;

    private ActivityResultLauncher<String> storagePermissionLauncher;
    private PtVideo pendingDownloadVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_peertube_channels);

        recyclerList = findViewById(R.id.recyclerList);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressLoading = findViewById(R.id.progressLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        textEmptyMessage = findViewById(R.id.textEmptyMessage);
        textHeaderTitle = findViewById(R.id.textHeaderTitle);
        buttonBack = findViewById(R.id.buttonBack);

        apiClient = new PeerTubeApiClient(this, new OkHttpClient());

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && pendingDownloadVideo != null) {
                        resolveAndDownload(pendingDownloadVideo);
                    } else if (!granted) {
                        Toast.makeText(this, R.string.download_permission_needed, Toast.LENGTH_LONG).show();
                    }
                    pendingDownloadVideo = null;
                });

        channelAdapter = new PtChannelAdapter(this::openChannel);
        videoAdapter = new PtVideoAdapter(new PtVideoAdapter.OnVideoActionListener() {
            @Override
            public void onVideoClick(PtVideo video) {
                openVideoPage(video);
            }

            @Override
            public void onDownloadClick(PtVideo video) {
                requestDownload(video);
            }

            @Override
            public void onShareClick(PtVideo video) {
                shareVideo(video);
            }
        });

        recyclerList.setLayoutManager(new LinearLayoutManager(this));
        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(this::reloadCurrentScreen);
        buttonBack.setOnClickListener(v -> showChannels());

        showChannels();
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == Screen.VIDEOS) {
            showChannels();
        } else {
            super.onBackPressed();
        }
    }

    private void reloadCurrentScreen() {
        if (currentScreen == Screen.CHANNELS) {
            loadChannels();
        } else if (currentChannel != null) {
            loadVideos(currentChannel);
        }
    }

    // --- Channels screen ---------------------------------------------------------------

    private void showChannels() {
        currentScreen = Screen.CHANNELS;
        currentChannel = null;
        textHeaderTitle.setText(R.string.peertube_channels_title);
        buttonBack.setVisibility(View.INVISIBLE);
        recyclerList.setAdapter(channelAdapter);
        loadChannels();
    }

    private void loadChannels() {
        if (!swipeRefresh.isRefreshing()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);

        apiClient.fetchChannels(new PeerTubeApiClient.ChannelsCallback() {
            @Override
            public void onSuccess(List<PtChannel> channels) {
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (channels.isEmpty()) {
                    textEmptyMessage.setText(R.string.peertube_channels_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    channelAdapter.submitList(channels);
                }
            }

            @Override
            public void onError(String message) {
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(R.string.peertube_channels_error);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- Videos screen -------------------------------------------------------------------

    private void openChannel(PtChannel channel) {
        currentScreen = Screen.VIDEOS;
        currentChannel = channel;
        textHeaderTitle.setText(channel.displayName.isEmpty() ? channel.name : channel.displayName);
        buttonBack.setVisibility(View.VISIBLE);
        recyclerList.setAdapter(videoAdapter);
        loadVideos(channel);
    }

    private void loadVideos(PtChannel channel) {
        if (!swipeRefresh.isRefreshing()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);

        apiClient.fetchChannelVideos(channel.name, new PeerTubeApiClient.VideosCallback() {
            @Override
            public void onSuccess(List<PtVideo> videos) {
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (videos.isEmpty()) {
                    textEmptyMessage.setText(R.string.peertube_videos_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    videoAdapter.submitList(videos);
                }
            }

            @Override
            public void onError(String message) {
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(R.string.peertube_videos_error);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void openVideoPage(PtVideo video) {
        if (video.pageUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(video.pageUrl)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.about_link_error, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Download ------------------------------------------------------------------------

    /**
     * On API 23-28, writing to the public Downloads directory via DownloadManager
     * requires the runtime WRITE_EXTERNAL_STORAGE permission; from API 29 onward
     * DownloadManager can write to public directories without it.
     */
    private void requestDownload(PtVideo video) {
        boolean needsRuntimePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
        boolean granted = !needsRuntimePermission || ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            resolveAndDownload(video);
        } else {
            pendingDownloadVideo = video;
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    /** Resolves the video's best file URL (requires a details API call) then enqueues it. */
    private void resolveAndDownload(PtVideo video) {
        Toast.makeText(this, R.string.peertube_resolving_download, Toast.LENGTH_SHORT).show();
        apiClient.fetchDownloadUrl(video.id, new PeerTubeApiClient.DownloadUrlCallback() {
            @Override
            public void onSuccess(String downloadUrl, String suggestedFileName) {
                startDownload(video, downloadUrl, suggestedFileName);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(PeerTubeChannelsActivity.this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startDownload(PtVideo video, String downloadUrl, String fileName) {
        try {
            Uri uri = Uri.parse(downloadUrl);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(video.name.isEmpty() ? getString(R.string.app_name) : video.name)
                    .setDescription(getString(R.string.app_name))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true);

            DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadManager.enqueue(request);
                Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Share ---------------------------------------------------------------------------

    private void shareVideo(PtVideo video) {
        String title = video.name.isEmpty() ? getString(R.string.app_name) : video.name;
        String shareText = getString(R.string.share_text_format, title, video.pageUrl);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
    }
}
