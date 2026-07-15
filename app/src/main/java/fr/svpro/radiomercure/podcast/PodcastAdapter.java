package fr.svpro.radiomercure.podcast;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;

import fr.svpro.radiomercure.R;

public class PodcastAdapter extends RecyclerView.Adapter<PodcastAdapter.EpisodeViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(Episode episode);
        void onDownloadClick(Episode episode);
        void onShareClick(Episode episode);
    }

    private final List<Episode> episodes = new ArrayList<>();
    private final OnEpisodeClickListener listener;

    public PodcastAdapter(OnEpisodeClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<Episode> newEpisodes) {
        episodes.clear();
        episodes.addAll(newEpisodes);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EpisodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_episode, parent, false);
        return new EpisodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodeViewHolder holder, int position) {
        Episode episode = episodes.get(position);
        holder.title.setText(episode.title.isEmpty() ? "-" : episode.title);
        holder.date.setText(episode.pubDate);

        boolean isVideo = episode.isVideo();
        holder.badge.setText(isVideo ? R.string.podcast_video_badge : R.string.podcast_audio_badge);
        holder.badge.setBackgroundResource(isVideo ? R.drawable.bg_badge_video : R.drawable.bg_badge_audio);

        if (!episode.thumbnailUrl.isEmpty()) {
            Glide.with(holder.thumb.getContext())
                    .load(episode.thumbnailUrl)
                    .apply(new RequestOptions()
                            .placeholder(R.drawable.ic_placeholder_cover)
                            .error(R.drawable.ic_placeholder_cover))
                    .into(holder.thumb);
        } else {
            holder.thumb.setImageResource(R.drawable.ic_placeholder_cover);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEpisodeClick(episode);
        });
        holder.buttonDownload.setOnClickListener(v -> {
            if (listener != null) listener.onDownloadClick(episode);
        });
        holder.buttonShare.setOnClickListener(v -> {
            if (listener != null) listener.onShareClick(episode);
        });
    }

    @Override
    public int getItemCount() {
        return episodes.size();
    }

    static class EpisodeViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView date;
        final TextView badge;
        final ImageButton buttonDownload;
        final ImageButton buttonShare;

        EpisodeViewHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.imageThumb);
            title = itemView.findViewById(R.id.textEpisodeTitle);
            date = itemView.findViewById(R.id.textEpisodeDate);
            badge = itemView.findViewById(R.id.badgeType);
            buttonDownload = itemView.findViewById(R.id.buttonDownload);
            buttonShare = itemView.findViewById(R.id.buttonShare);
        }
    }
}
