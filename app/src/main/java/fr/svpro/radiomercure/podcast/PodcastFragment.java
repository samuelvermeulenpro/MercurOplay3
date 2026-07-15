package fr.svpro.radiomercure.podcast;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import fr.svpro.radiomercure.R;
import okhttp3.OkHttpClient;

public class PodcastFragment extends Fragment {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private TextView textEmptyMessage;

    private PodcastAdapter adapter;
    private PodcastRepository repository;

    private ActivityResultLauncher<String> storagePermissionLauncher;
    private Episode pendingDownloadEpisode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_podcast, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.recyclerEpisodes);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        progressLoading = view.findViewById(R.id.progressLoading);
        layoutEmpty = view.findViewById(R.id.layoutEmpty);
        textEmptyMessage = view.findViewById(R.id.textEmptyMessage);

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && pendingDownloadEpisode != null) {
                        startDownload(pendingDownloadEpisode);
                    } else if (!granted) {
                        Toast.makeText(requireContext(),
                                R.string.download_permission_needed, Toast.LENGTH_LONG).show();
                    }
                    pendingDownloadEpisode = null;
                });

        adapter = new PodcastAdapter(new PodcastAdapter.OnEpisodeClickListener() {
            @Override
            public void onEpisodeClick(Episode episode) {
                openEpisode(episode);
            }

            @Override
            public void onDownloadClick(Episode episode) {
                requestDownload(episode);
            }

            @Override
            public void onShareClick(Episode episode) {
                shareEpisode(episode);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(this::loadFeed);

        repository = new PodcastRepository(new OkHttpClient());

        loadFeed();
    }

    private void loadFeed() {
        if (!swipeRefresh.isRefreshing()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);

        repository.fetchFeed(new PodcastRepository.FeedCallback() {
            @Override
            public void onSuccess(List<Episode> episodes) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                if (episodes.isEmpty()) {
                    textEmptyMessage.setText(R.string.podcast_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    adapter.submitList(episodes);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(R.string.podcast_error);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void openEpisode(Episode episode) {
        Intent intent = new Intent(requireContext(), PodcastPlayerActivity.class);
        intent.putExtra(PodcastPlayerActivity.EXTRA_EPISODE, episode);
        startActivity(intent);
    }

    /**
     * On API 23-28, writing to the public Downloads directory via
     * DownloadManager requires the runtime WRITE_EXTERNAL_STORAGE permission.
     * From API 29 onward DownloadManager can write to public directories
     * without it, so we only gate the request for the older range.
     */
    private void requestDownload(Episode episode) {
        boolean needsRuntimePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
        boolean granted = !needsRuntimePermission || ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        if (granted) {
            startDownload(episode);
        } else {
            pendingDownloadEpisode = episode;
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void startDownload(Episode episode) {
        if (episode.mediaUrl == null || episode.mediaUrl.isEmpty()) {
            Toast.makeText(requireContext(), R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = Uri.parse(episode.mediaUrl);
            String fileName = buildFileName(episode);

            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(episode.title.isEmpty() ? getString(R.string.app_name) : episode.title)
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

    private String buildFileName(Episode episode) {
        String base = episode.title.isEmpty() ? "episode_mercuroplay" : episode.title;
        String sanitized = base.replaceAll("[^a-zA-Z0-9À-ÿ _-]", "").trim();
        if (sanitized.isEmpty()) sanitized = "episode_mercuroplay";

        String extension = ".mp4";
        String lowerUrl = episode.mediaUrl.toLowerCase();
        if (lowerUrl.contains(".mp3")) extension = ".mp3";
        else if (lowerUrl.contains(".m4a")) extension = ".m4a";
        else if (lowerUrl.contains(".mov")) extension = ".mov";
        else if (lowerUrl.contains(".webm")) extension = ".webm";
        else if (!episode.isVideo()) extension = ".mp3";

        return sanitized + extension;
    }

    private void shareEpisode(Episode episode) {
        String title = episode.title.isEmpty() ? getString(R.string.app_name) : episode.title;
        String urlToShare = !episode.pageUrl.isEmpty() ? episode.pageUrl : episode.mediaUrl;
        String shareText = getString(R.string.share_text_format, title, urlToShare);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
    }
}
