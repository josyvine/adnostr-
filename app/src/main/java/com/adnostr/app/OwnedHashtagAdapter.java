package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemOwnedHashtagBinding;

import java.util.List;

/**
 * Adapter for the Advertiser's Hashtag Registry.
 * FEATURE 2: Renders a list of hashtags exclusively owned by the advertiser.
 * Provides a callback interface to handle the "Release" (Kind 5) action.
 */
public class OwnedHashtagAdapter extends RecyclerView.Adapter<OwnedHashtagAdapter.HashtagViewHolder> {

    private final List<String> ownedHashtags;
    private final OnReleaseClickListener listener;

    /**
     * Interface to communicate release actions to the Registry Manager.
     */
    public interface OnReleaseClickListener {
        void onReleaseClicked(String tag, int position);
    }

    public OwnedHashtagAdapter(List<String> ownedHashtags, OnReleaseClickListener listener) {
        this.ownedHashtags = ownedHashtags;
        this.listener = listener;
    }

    @NonNull
    @Override
    public HashtagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemOwnedHashtagBinding binding = ItemOwnedHashtagBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new HashtagViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HashtagViewHolder holder, int position) {
        String tag = ownedHashtags.get(position);
        holder.bind(tag, position, listener);
    }

    @Override
    public int getItemCount() {
        return ownedHashtags != null ? ownedHashtags.size() : 0;
    }

    /**
     * ViewHolder class for the Hashtag Registry item.
     */
    static class HashtagViewHolder extends RecyclerView.ViewHolder {
        private final ItemOwnedHashtagBinding binding;

        public HashtagViewHolder(ItemOwnedHashtagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds the tag name and configures the release button click logic.
         */
        public void bind(String tag, int position, OnReleaseClickListener listener) {
            // Ensure tag starts with '#' for display
            if (!tag.startsWith("#")) {
                binding.tvOwnedTagName.setText("#" + tag);
            } else {
                binding.tvOwnedTagName.setText(tag);
            }

            // Setup the release button logic
            binding.btnReleaseHashtag.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onReleaseClicked(tag, position);
                }
            });
        }
    }
}