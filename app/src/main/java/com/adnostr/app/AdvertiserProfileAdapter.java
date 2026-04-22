package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.adnostr.app.databinding.ItemAdvertiserProfileBinding;
import com.google.android.material.chip.Chip;
import java.util.List;

/**
 * Adapter for the Advertiser Discovery Directory.
 * FEATURE 4: Renders business profiles found on the Nostr network.
 * Logic: Binds Name -> Injects Dynamic Topic Chips -> Handles Store Entry.
 */
public class AdvertiserProfileAdapter extends RecyclerView.Adapter<AdvertiserProfileAdapter.ProfileViewHolder> {

    private final List<BrowseAdvertisersActivity.AdvertiserProfile> profiles;
    private final OnProfileClickListener listener;

    /**
     * Interface to handle navigation to an advertiser's individual product store.
     */
    public interface OnProfileClickListener {
        void onProfileClicked(BrowseAdvertisersActivity.AdvertiserProfile profile);
    }

    public AdvertiserProfileAdapter(List<BrowseAdvertisersActivity.AdvertiserProfile> profiles, OnProfileClickListener listener) {
        this.profiles = profiles;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAdvertiserProfileBinding binding = ItemAdvertiserProfileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ProfileViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
        holder.bind(profiles.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return profiles != null ? profiles.size() : 0;
    }

    static class ProfileViewHolder extends RecyclerView.ViewHolder {
        private final ItemAdvertiserProfileBinding binding;

        public ProfileViewHolder(ItemAdvertiserProfileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds business data and generates dynamic UI chips for categories.
         */
        public void bind(BrowseAdvertisersActivity.AdvertiserProfile profile, OnProfileClickListener listener) {
            // 1. Set Identity Info
            binding.tvBusinessName.setText(profile.name);
            
            // 2. Clear and Populate Topic Chips dynamically
            binding.cgBusinessTopics.removeAllViews();
            if (profile.topics != null) {
                for (String topic : profile.topics) {
                    addCategoryChip(topic);
                }
            }

            // 3. Setup Click Listener for Marketplace Entry
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onProfileClicked(profile);
                }
            });
        }

        /**
         * Helper to create a stylized small chip for the business category.
         */
        private void addCategoryChip(String text) {
            Chip chip = new Chip(itemView.getContext());
            chip.setText(text);
            chip.setChipBackgroundColorResource(R.color.hfs_border_grey);
            chip.setTextColor(itemView.getResources().getColor(android.R.color.darker_gray));
            chip.setTextSize(10f);
            chip.setChipMinTouchTargetSize(0); // Compact style for lists
            chip.setClickable(false);
            chip.setCheckable(false);
            
            // Ensure chips wrap content properly in the flow layout
            binding.cgBusinessTopics.addView(chip);
        }
    }
}