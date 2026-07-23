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

import fr.svpro.radiomercure.R;

public class PtChannelAdapter extends RecyclerView.Adapter<PtChannelAdapter.ChannelViewHolder> {

    public interface OnChannelClickListener {
        void onChannelClick(PtChannel channel);
    }

    private final List<PtChannel> channels = new ArrayList<>();
    private final OnChannelClickListener listener;

    public PtChannelAdapter(OnChannelClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<PtChannel> newChannels) {
        channels.clear();
        channels.addAll(newChannels);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        PtChannel channel = channels.get(position);
        holder.name.setText(channel.displayName.isEmpty() ? channel.name : channel.displayName);
        holder.description.setText(channel.description);
        holder.description.setVisibility(channel.description.isEmpty() ? View.GONE : View.VISIBLE);

        if (!channel.avatarUrl.isEmpty()) {
            Glide.with(holder.avatar.getContext())
                    .load(channel.avatarUrl)
                    .apply(new RequestOptions()
                            .placeholder(R.drawable.ic_placeholder_cover)
                            .error(R.drawable.ic_placeholder_cover))
                    .into(holder.avatar);
        } else {
            holder.avatar.setImageResource(R.drawable.ic_placeholder_cover);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChannelClick(channel);
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView description;

        ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.imageChannelAvatar);
            name = itemView.findViewById(R.id.textChannelName);
            description = itemView.findViewById(R.id.textChannelDescription);
        }
    }
}
