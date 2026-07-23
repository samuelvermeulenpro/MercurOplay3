package fr.svpro.radiomercure.peertube;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.svpro.radiomercure.R;

public class PtVideoAdapter extends RecyclerView.Adapter<PtVideoAdapter.VideoViewHolder> {

    public interface OnVideoActionListener {
        void onVideoClick(PtVideo video);
        void onDownloadClick(PtVideo video);
        void onShareClick(PtVideo video);
    }

    private final List<PtVideo> videos = new ArrayList<>();
    private final OnVideoActionListener listener;

    public PtVideoAdapter(OnVideoActionListener listener) {
        this.listener = listener;
    }

    public void submitList(List<PtVideo> newVideos) {
        videos.clear();
        videos.addAll(newVideos);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pt_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        PtVideo video = videos.get(position);
        holder.title.setText(video.name.isEmpty() ? "-" : video.name);
        holder.duration.setText(formatDuration(video.durationSeconds));
        holder.duration.setVisibility(video.durationSeconds > 0 ? View.VISIBLE : View.GONE);

        if (!video.thumbnailUrl.isEmpty()) {
            Glide.with(holder.thumb.getContext())
                    .load(video.thumbnailUrl)
                    .apply(new RequestOptions()
                            .placeholder(R.drawable.ic_placeholder_cover)
                            .error(R.drawable.ic_placeholder_cover))
                    .into(holder.thumb);
        } else {
            holder.thumb.setImageResource(R.drawable.ic_placeholder_cover);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onVideoClick(video);
        });
        holder.buttonDownload.setOnClickListener(v -> {
            if (listener != null) listener.onDownloadClick(video);
        });
        holder.buttonShare.setOnClickListener(v -> {
            if (listener != null) listener.onShareClick(video);
        });
    }

    @Override
    public int getItemCount() {
        return videos.size();
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) return "";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView duration;
        final ImageView buttonDownload;
        final ImageView buttonShare;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.imageThumb);
            title = itemView.findViewById(R.id.textVideoTitle);
            duration = itemView.findViewById(R.id.badgeDuration);
            buttonDownload = itemView.findViewById(R.id.buttonDownload);
            buttonShare = itemView.findViewById(R.id.buttonShare);
        }
    }
}
