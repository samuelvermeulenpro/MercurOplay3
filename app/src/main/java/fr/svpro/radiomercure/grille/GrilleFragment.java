package fr.svpro.radiomercure.grille;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.tabs.TabLayout;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import fr.svpro.radiomercure.R;
import fr.svpro.radiomercure.peertube.PeerTubeApiClient;
import fr.svpro.radiomercure.peertube.PeerTubePlayerActivity;
import fr.svpro.radiomercure.peertube.PtVideo;

/**
 * "Grille" tab: the programme schedule for the next 7 days, read from the Nextcloud
 * calendar like the website's grid (see the Radio Mercure WordPress theme).
 */
public class GrilleFragment extends Fragment {

    private static final int DAYS = 7;
    private static final long NOW_TICK_MS = 60_000L;
    private static final String STATE_DAY = "selected_day";
    private static final DateTimeFormatter TAB_DATE = DateTimeFormatter.ofPattern("d/MM", Locale.FRANCE);
    private static final DateTimeFormatter TAB_DAY = DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE);

    private TabLayout tabDays;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerSlots;
    private ProgressBar progressLoading;
    private LinearLayout layoutEmpty;
    private ImageView imageEmpty;
    private TextView textEmptyMessage;
    private TextView textStale;

    private SlotAdapter adapter;
    private ScheduleRepository repository;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable nowTick = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) adapter.setNow(nowSec());
            handler.postDelayed(this, NOW_TICK_MS);
        }
    };

    private List<IcsEvent> events;
    private LocalDate today;
    private int selectedDay;
    private boolean scrollToOnAir = true;
    private boolean resolvingReplay;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_grille, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tabDays = view.findViewById(R.id.tabDays);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        recyclerSlots = view.findViewById(R.id.recyclerSlots);
        progressLoading = view.findViewById(R.id.progressLoading);
        layoutEmpty = view.findViewById(R.id.layoutEmpty);
        imageEmpty = view.findViewById(R.id.imageEmpty);
        textEmptyMessage = view.findViewById(R.id.textEmptyMessage);
        textStale = view.findViewById(R.id.textStale);

        adapter = new SlotAdapter(this::openLink);
        recyclerSlots.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerSlots.setAdapter(adapter);

        swipeRefresh.setColorSchemeResources(R.color.brand_orange, R.color.brand_blue);
        swipeRefresh.setOnRefreshListener(() -> load(true));

        selectedDay = savedInstanceState != null ? savedInstanceState.getInt(STATE_DAY, 0) : 0;
        buildTabs();
        tabDays.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectedDay = tab.getPosition();
                scrollToOnAir = selectedDay == 0;
                showDay();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        repository = ScheduleRepository.getInstance(requireContext());
        if (!ScheduleRepository.isConfigured()) {
            showMessage(R.drawable.ic_error, getString(R.string.grille_not_configured));
            swipeRefresh.setEnabled(false);
            return;
        }
        load(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The app may have stayed in the background past midnight: rebuild the day tabs.
        if (today != null && !today.equals(LocalDate.now(ScheduleRepository.STATION_ZONE))) {
            selectedDay = 0;
            buildTabs();
            showDay();
        }
        handler.postDelayed(nowTick, NOW_TICK_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(nowTick);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_DAY, selectedDay);
    }

    private void buildTabs() {
        today = LocalDate.now(ScheduleRepository.STATION_ZONE);
        tabDays.removeAllTabs();
        for (int i = 0; i < DAYS; i++) {
            LocalDate d = today.plusDays(i);
            String label;
            if (i == 0) {
                label = getString(R.string.grille_today);
            } else if (i == 1) {
                label = getString(R.string.grille_tomorrow);
            } else {
                String day = TAB_DAY.format(d);
                label = day.substring(0, 1).toUpperCase(Locale.FRANCE) + day.substring(1);
            }
            tabDays.addTab(tabDays.newTab().setText(label + " " + TAB_DATE.format(d)), i == selectedDay);
        }
    }

    private void load(boolean force) {
        if (!swipeRefresh.isRefreshing()) progressLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);

        repository.load(force, new ScheduleRepository.Callback() {
            @Override
            public void onLoaded(List<IcsEvent> loaded, boolean stale) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                textStale.setVisibility(stale ? View.VISIBLE : View.GONE);
                events = loaded;
                showDay();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                progressLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                adapter.submit(Collections.emptyList(), 0, nowSec());
                showMessage(R.drawable.ic_error, getString(R.string.grille_error) + "\n" + message);
            }
        });
    }

    /** Displays the selected day: every show overlapping it, including one started the day before. */
    private void showDay() {
        if (events == null) return;
        long dayStart = today.plusDays(selectedDay).atStartOfDay(ScheduleRepository.STATION_ZONE).toEpochSecond();
        long dayEnd = today.plusDays(selectedDay + 1).atStartOfDay(ScheduleRepository.STATION_ZONE).toEpochSecond();

        List<Occurrence> day = new ArrayList<>();
        for (Occurrence o : IcsExpander.expand(events, dayStart, dayEnd)) {
            if (o.start < dayEnd && o.end > dayStart) day.add(o);
        }
        adapter.submit(day, dayStart, nowSec());

        if (day.isEmpty()) {
            showMessage(R.drawable.ic_headset, getString(R.string.grille_empty_day));
            return;
        }
        layoutEmpty.setVisibility(View.GONE);
        int onAir = adapter.onAirPosition();
        if (scrollToOnAir && onAir > 0) {
            ((LinearLayoutManager) recyclerSlots.getLayoutManager()).scrollToPositionWithOffset(onAir, 0);
        } else {
            recyclerSlots.scrollToPosition(0);
        }
        scrollToOnAir = false;
    }

    private void showMessage(int icon, String message) {
        imageEmpty.setImageResource(icon);
        textEmptyMessage.setText(message);
        layoutEmpty.setVisibility(View.VISIBLE);
    }

    /**
     * A MercurOplay replay plays in the app's PeerTube player, a MercurOplay channel opens
     * in the "Émissions" tab; any other link (or a replay the API won't give) in the browser.
     */
    private void openLink(Occurrence o, String url) {
        String videoId = OplayLinks.videoId(url);
        if (videoId != null) {
            playReplay(videoId, url);
            return;
        }
        String channel = OplayLinks.channelName(url);
        if (channel != null) {
            Bundle args = new Bundle();
            args.putString("channelName", channel);
            args.putString("channelDisplayName", url.equals(o.link) ? o.title : channel);
            NavHostFragment.findNavController(this).navigate(R.id.action_grille_to_videos, args);
            return;
        }
        openInBrowser(url);
    }

    private void playReplay(String videoId, String url) {
        if (resolvingReplay) return;
        resolvingReplay = true;
        Toast.makeText(requireContext(), R.string.peertube_resolving_playback, Toast.LENGTH_SHORT).show();
        PeerTubeApiClient.getInstance(requireContext()).fetchVideo(videoId, new PeerTubeApiClient.VideoCallback() {
            @Override
            public void onSuccess(PtVideo video) {
                resolvingReplay = false;
                if (!isAdded()) return;
                Intent intent = new Intent(requireContext(), PeerTubePlayerActivity.class);
                intent.putExtra(PeerTubePlayerActivity.EXTRA_VIDEO, video);
                startActivity(intent);
            }

            @Override
            public void onError(String message) {
                resolvingReplay = false;
                if (!isAdded()) return;
                openInBrowser(url);
            }
        });
    }

    private void openInBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.about_link_error, Toast.LENGTH_SHORT).show();
        }
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }
}
