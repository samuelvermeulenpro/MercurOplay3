package fr.svpro.radiomercure.peertube;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

import fr.svpro.radiomercure.R;

/** Top-level "Chaînes" tab: lists the PeerTube channels of the authenticated user. */
public class PeerTubeChannelsFragment extends Fragment {

    /** Default is alphabetical, per spec; the other two are by creation date. */
    private enum SortOrder { ALPHABETICAL, NEWEST_FIRST, OLDEST_FIRST }

    private RecyclerView recyclerList;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private TextView textEmptyMessage;
    private TextView textHeader;
    private ImageButton buttonSort;

    private PtChannelAdapter adapter;
    private PeerTubeApiClient apiClient;

    // Holds the last successful fetch as returned by the server, so switching sort order
    // just re-sorts this in memory instead of re-fetching from the network.
    private final List<PtChannel> loadedChannels = new ArrayList<>();
    private SortOrder sortOrder = SortOrder.ALPHABETICAL;

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
        buttonSort = view.findViewById(R.id.buttonSort);

        ImageButton buttonBack = view.findViewById(R.id.buttonBack);
        buttonBack.setVisibility(View.GONE);
        textHeader.setText(R.string.peertube_channels_title);
        buttonSort.setVisibility(View.VISIBLE);
        buttonSort.setOnClickListener(this::showSortMenu);

        adapter = new PtChannelAdapter(this::openChannel);
        recyclerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerList.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(this::loadChannels);

        apiClient = PeerTubeApiClient.getInstance(requireContext());

        loadChannels();
    }

    private void showSortMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 0, 0, R.string.peertube_sort_alphabetical);
        popup.getMenu().add(0, 1, 1, R.string.peertube_sort_newest_first);
        popup.getMenu().add(0, 2, 2, R.string.peertube_sort_oldest_first);
        popup.setOnMenuItemClickListener(item -> {
            SortOrder selected;
            if (item.getItemId() == 1) {
                selected = SortOrder.NEWEST_FIRST;
            } else if (item.getItemId() == 2) {
                selected = SortOrder.OLDEST_FIRST;
            } else {
                selected = SortOrder.ALPHABETICAL;
            }
            if (selected != sortOrder) {
                sortOrder = selected;
                displaySortedChannels();
            }
            return true;
        });
        popup.show();
    }

    private void loadChannels() {
        if (!swipeRefresh.isRefreshing()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        layoutEmpty.setVisibility(View.GONE);

        apiClient.fetchChannels(new PeerTubeApiClient.ChannelsCallback() {
            @Override
            public void onSuccess(List<PtChannel> channels) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                loadedChannels.clear();
                loadedChannels.addAll(channels);
                if (channels.isEmpty()) {
                    textEmptyMessage.setText(R.string.peertube_channels_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    displaySortedChannels();
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(getString(R.string.peertube_channels_error) + "\n" + message);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Sorts a copy of {@link #loadedChannels} per {@link #sortOrder} and displays it. */
    private void displaySortedChannels() {
        List<PtChannel> sorted = new ArrayList<>(loadedChannels);
        switch (sortOrder) {
            case NEWEST_FIRST:
                // ISO 8601 UTC timestamps sort correctly with plain string comparison.
                sorted.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
                break;
            case OLDEST_FIRST:
                sorted.sort((a, b) -> a.createdAt.compareTo(b.createdAt));
                break;
            case ALPHABETICAL:
            default:
                sorted.sort((a, b) -> displayNameOf(a).compareToIgnoreCase(displayNameOf(b)));
                break;
        }
        adapter.submitList(sorted);
    }

    private String displayNameOf(PtChannel channel) {
        return channel.displayName.isEmpty() ? channel.name : channel.displayName;
    }

    private void openChannel(PtChannel channel) {
        Bundle args = new Bundle();
        args.putString("channelName", channel.name);
        args.putString("channelDisplayName", channel.displayName.isEmpty() ? channel.name : channel.displayName);
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_channels_to_videos, args);
    }
}
