package fr.svpro.radiomercure.grille;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.svpro.radiomercure.R;

/**
 * One day of the schedule. Each slot shows its time range, title and host; tapping a slot
 * that has details (image, description or link) expands it, like the website's grid.
 */
public class SlotAdapter extends RecyclerView.Adapter<SlotAdapter.SlotViewHolder> {

    public interface OnLinkClickListener {
        void onLinkClick(Occurrence occurrence);
    }

    private static final Locale FRENCH = Locale.FRANCE;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("H'h'mm", FRENCH);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM · H'h'mm", FRENCH);

    private final List<Occurrence> slots = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final OnLinkClickListener listener;
    private long dayStart;
    private long now;

    public SlotAdapter(OnLinkClickListener listener) {
        this.listener = listener;
    }

    /** {@code dayStart}: start of the displayed day, to flag shows continuing from the day before. */
    public void submit(List<Occurrence> newSlots, long dayStart, long now) {
        slots.clear();
        slots.addAll(newSlots);
        this.dayStart = dayStart;
        this.now = now;
        notifyDataSetChanged();
    }

    public void setNow(long now) {
        this.now = now;
        notifyDataSetChanged();
    }

    /** Position of the show on air right now, or -1. */
    public int onAirPosition() {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).isOnAirAt(now)) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public SlotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_slot, parent, false);
        return new SlotViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SlotViewHolder h, int position) {
        Occurrence o = slots.get(position);
        boolean continued = o.start < dayStart;
        boolean onAir = o.isOnAirAt(now);
        String description = o.description;
        boolean hasDetail = !description.isEmpty() || !o.image.isEmpty() || !o.link.isEmpty();
        boolean isExpanded = hasDetail && expanded.contains(o.uid);

        h.time.setText(h.itemView.getContext().getString(R.string.grille_time_range,
                continued ? "0h00" : format(HOUR, o.start), format(HOUR, o.end)));
        h.title.setText(continued
                ? h.itemView.getContext().getString(R.string.grille_continued, o.title)
                : o.title);
        h.host.setText(o.host);
        h.host.setVisibility(o.host.isEmpty() ? View.GONE : View.VISIBLE);
        h.onAir.setVisibility(onAir ? View.VISIBLE : View.GONE);
        float density = h.itemView.getResources().getDisplayMetrics().density;
        h.card.setStrokeWidth(onAir ? Math.round(2 * density) : 0);

        h.chevron.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        h.chevron.setRotation(isExpanded ? 180f : 0f);
        h.head.setClickable(hasDetail);
        h.head.setOnClickListener(hasDetail ? v -> {
            if (!expanded.remove(o.uid)) expanded.add(o.uid);
            notifyItemChanged(h.getBindingAdapterPosition());
        } : null);

        h.detail.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        if (!isExpanded) {
            Glide.with(h.image.getContext()).clear(h.image);
            return;
        }

        if (o.image.isEmpty()) {
            Glide.with(h.image.getContext()).clear(h.image);
            h.image.setVisibility(View.GONE);
        } else {
            h.image.setVisibility(View.VISIBLE);
            Glide.with(h.image.getContext()).load(o.image).into(h.image);
        }

        String when = capitalize(format(WHEN, o.start)) + " – " + format(HOUR, o.end);
        h.when.setText(o.host.isEmpty() ? when : when + " · " + o.host);
        h.description.setText(description);
        h.description.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);

        if (o.link.isEmpty()) {
            h.link.setVisibility(View.GONE);
        } else {
            h.link.setVisibility(View.VISIBLE);
            h.link.setText(isOplay(o.link) ? R.string.grille_link_oplay : R.string.grille_link_page);
            h.link.setOnClickListener(v -> listener.onLinkClick(o));
        }
    }

    @Override
    public int getItemCount() {
        return slots.size();
    }

    static boolean isOplay(String url) {
        return url.matches("(?i)^https?://[^/]*oplay[^/]*(/.*)?$");
    }

    private static String format(DateTimeFormatter f, long epochSec) {
        return f.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ScheduleRepository.STATION_ZONE));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(FRENCH) + s.substring(1);
    }

    static class SlotViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View head;
        final TextView time;
        final TextView onAir;
        final TextView title;
        final TextView host;
        final ImageView chevron;
        final LinearLayout detail;
        final ImageView image;
        final TextView when;
        final TextView description;
        final MaterialButton link;

        SlotViewHolder(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.cardSlot);
            head = v.findViewById(R.id.layoutHead);
            time = v.findViewById(R.id.textTime);
            onAir = v.findViewById(R.id.textOnAir);
            title = v.findViewById(R.id.textTitle);
            host = v.findViewById(R.id.textHost);
            chevron = v.findViewById(R.id.imageChevron);
            detail = v.findViewById(R.id.layoutDetail);
            image = v.findViewById(R.id.imageSlot);
            when = v.findViewById(R.id.textWhen);
            description = v.findViewById(R.id.textDescription);
            link = v.findViewById(R.id.buttonLink);
        }
    }
}
