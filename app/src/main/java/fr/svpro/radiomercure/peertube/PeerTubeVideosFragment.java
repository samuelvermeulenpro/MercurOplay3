package fr.svpro.radiomercure.peertube;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import fr.svpro.radiomercure.R;
import okhttp3.OkHttpClient;

/** Videos of a single PeerTube channel, reached from {@link PeerTubeChannelsFragment}. */
public class PeerTubeVideosFragment extends Fragment {

    private RecyclerView recyclerList;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private TextView textEmptyMessage;
    private TextView textHeader;

    private PtVideoAdapter adapter;
    private PeerTubeApiClient apiClient;
    private String channelName;

    private ActivityResultLauncher<String> storagePermissionLauncher;
    private PtVideo pendingDownloadVideo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_peertube_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerList = view.findViewById(R.id.recyclerList);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        progressLoading = view.findViewById(R.id.progressLoading);
        layoutEmpty = view.findViewById(R.id.layoutEmpty);
        textEmptyMessage = view.findViewById(R.id.textEmptyMessage);
        textHeader = view.findViewById(R.id.textHeader);

        Bundle args = getArguments();
        channelName = args != null ? args.getString("channelName", "") : "";
        String channelDisplayName = args != null ? args.getString("channelDisplayName", "") : "";
        textHeader.setText(channelDisplayName.isEmpty() ? getString(R.string.peertube_channels_title) : channelDisplayName);

        ImageButton buttonBack = view.findViewById(R.id.buttonBack);
        buttonBack.setVisibility(View.VISIBLE);
        buttonBack.setOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && pendingDownloadVideo != null) {
                        resolveAndDownload(pendingDownloadVideo);
                    } else if (!granted) {
                        Toast.makeText(requireContext(), R.string.download_permission_needed, Toast.LENGTH_LONG).show();
                    }
                    pendingDownloadVideo = null;
                });

        adapter = new PtVideoAdapter(new PtVideoAdapter.OnVideoActionListener() {
            @Override
            public void onVideoClick(PtVideo video) {
                openPlayer(video);
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
        recyclerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerList.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(this::loadVideos);

        apiClient = new PeerTubeApiClient(requireContext(), new OkHttpClient());

        loadVideos();
    }

    private void loadVideos() {
        if (channelName.isEmpty()) return;
        if (!swipeRefresh.isRefreshing()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);

        apiClient.fetchChannelVideos(channelName, new PeerTubeApiClient.VideosCallback() {
            @Override
            public void onSuccess(List<PtVideo> videos) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (videos.isEmpty()) {
                    textEmptyMessage.setText(R.string.peertube_videos_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    adapter.submitList(videos);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(R.string.peertube_videos_error);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- Playback --------------------------------------------------------------------

    private void openPlayer(PtVideo video) {
        Intent intent = new Intent(requireContext(), PeerTubePlayerActivity.class);
        intent.putExtra(PeerTubePlayerActivity.EXTRA_VIDEO, video);
        startActivity(intent);
    }

    // --- Download --------------------------------------------------------------------

    /**
     * On API 23-28, writing to the public Downloads directory via DownloadManager
     * requires the runtime WRITE_EXTERNAL_STORAGE permission; from API 29 onward
     * DownloadManager can write to public directories without it.
     */
    private void requestDownload(PtVideo video) {
        boolean needsRuntimePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
        boolean granted = !needsRuntimePermission || ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            resolveAndDownload(video);
        } else {
            pendingDownloadVideo = video;
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void resolveAndDownload(PtVideo video) {
        Toast.makeText(requireContext(), R.string.peertube_resolving_download, Toast.LENGTH_SHORT).show();
        apiClient.fetchDownloadUrl(video.id, new PeerTubeApiClient.DownloadUrlCallback() {
            @Override
            public void onSuccess(String downloadUrl, String suggestedFileName) {
                if (!isAdded()) return;
                startDownload(video, downloadUrl, suggestedFileName);
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.download_failed, Toast.LENGTH_SHORT).show();
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

            DownloadManager downloadManager =
                    (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadManager.enqueue(request);
                Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.download_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.download_failed, Toast.LENGTH_SHORT).show();
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
