package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemHashtagBinding;

import java.util.List;
import java.util.Set;

/**
 * Adapter for the Interest Selection Grid.
 * Binds hashtag strings to toggleable chip UI items.
 */
public class HashtagAdapter extends RecyclerView.Adapter<HashtagAdapter.HashtagViewHolder> {

    private final List<String> hashtags;
    private final Set<String> selectedHashtags;
    private final OnHashtagClickListener listener;

    /**
     * Interface to communicate selection changes back to the Dashboard Fragment.
     */
    public interface OnHashtagClickListener {
        void onHashtagToggled(String hashtag, boolean isSelected);
    }

    /**
     * Constructor for the adapter.
     * @param hashtags All available interest hashtags.
     * @param selectedHashtags Set of hashtags the user already follows.
     * @param listener Callback for toggle events.
     */
    public HashtagAdapter(List<String> hashtags, Set<String> selectedHashtags, OnHashtagClickListener listener) {
        this.hashtags = hashtags;
        this.selectedHashtags = selectedHashtags;
        this.listener = listener;
    }

    @NonNull
    @Override
    public HashtagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Initialize ViewBinding for the single hashtag item
        ItemHashtagBinding binding = ItemHashtagBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new HashtagViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HashtagViewHolder holder, int position) {
        String tag = hashtags.get(position);
        boolean isSelected = selectedHashtags.contains(tag);
        holder.bind(tag, isSelected, listener);
    }

    @Override
    public int getItemCount() {
        return hashtags != null ? hashtags.size() : 0;
    }

    /**
     * ViewHolder class that handles the visual state of a single hashtag chip.
     */
    static class HashtagViewHolder extends RecyclerView.ViewHolder {
        private final ItemHashtagBinding binding;

        public HashtagViewHolder(ItemHashtagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds data and sets up the click toggle logic.
         */
        public void bind(String tag, boolean isSelected, OnHashtagClickListener listener) {
            // Display hashtag with the '#' prefix
            binding.tvHashtagName.setText("#" + tag);

            // Update the visual state based on whether it is selected
            updateVisualState(isSelected);

            // Handle the click event to toggle interest
            binding.cvHashtagContainer.setOnClickListener(v -> {
                // Determine new state (toggle current)
                boolean newState = !binding.cvHashtagContainer.getTag().equals("SELECTED");
                
                // Update visuals instantly for better UX
                updateVisualState(newState);

                // Notify the dashboard to update the database
                if (listener != null) {
                    listener.onHashtagToggled(tag, newState);
                }
            });
        }

        /**
         * Changes colors and stroke to indicate selection status.
         */
        private void updateVisualState(boolean isSelected) {
            if (isSelected) {
                // Active/Selected state
                binding.cvHashtagContainer.setTag("SELECTED");
                binding.cvHashtagContainer.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_blue));
                binding.tvHashtagName.setTextColor(
                        ContextCompat.getColor(itemView.getContext(), android.R.color.white));
                binding.cvHashtagContainer.setStrokeWidth(0);
            } else {
                // Inactive/Unselected state
                binding.cvHashtagContainer.setTag("UNSELECTED");
                binding.cvHashtagContainer.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.getContext(), android.R.color.transparent));
                binding.tvHashtagName.setTextColor(
                        ContextCompat.getColor(itemView.getContext(), R.color.hfs_text_grey));
                binding.cvHashtagContainer.setStrokeColor(
                        ContextCompat.getColor(itemView.getContext(), R.color.hfs_border_grey));
                binding.cvHashtagContainer.setStrokeWidth(2);
            }
        }
    }
}