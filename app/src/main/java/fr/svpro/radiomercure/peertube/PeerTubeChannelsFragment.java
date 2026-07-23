package fr.svpro.radiomercure.peertube;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import fr.svpro.radiomercure.R;
import okhttp3.OkHttpClient;

/** Top-level "Chaînes" tab: lists the PeerTube channels of the authenticated user. */
public class PeerTubeChannelsFragment extends Fragment {

    private RecyclerView recyclerList;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private TextView textEmptyMessage;
    private TextView textHeader;

    private PtChannelAdapter adapter;
    private PeerTubeApiClient apiClient;

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

        ImageButton buttonBack = view.findViewById(R.id.buttonBack);
        buttonBack.setVisibility(View.GONE);
        textHeader.setText(R.string.peertube_channels_title);

        adapter = new PtChannelAdapter(this::openChannel);
        recyclerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerList.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(this::loadChannels);

        apiClient = new PeerTubeApiClient(requireContext(), new OkHttpClient());

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
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                if (channels.isEmpty()) {
                    textEmptyMessage.setText(R.string.peertube_channels_empty);
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    adapter.submitList(channels);
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textEmptyMessage.setText(R.string.peertube_channels_error);
                layoutEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void openChannel(PtChannel channel) {
        Bundle args = new Bundle();
        args.putString("channelName", channel.name);
        args.putString("channelDisplayName", channel.displayName.isEmpty() ? channel.name : channel.displayName);
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_channels_to_videos, args);
    }
}
