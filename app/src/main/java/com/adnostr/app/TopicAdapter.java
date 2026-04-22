package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemTopicCheckboxBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the Personalized Topics list.
 * FEATURE 4: Handles checkbox selection for business categories.
 * Logic: Tracks selection state -> Provides list of chosen topics to Activity.
 */
public class TopicAdapter extends RecyclerView.Adapter<TopicAdapter.TopicViewHolder> {

    private final List<String> availableTopics;
    private final Set<String> selectedTopics;

    public TopicAdapter(List<String> availableTopics, Set<String> previouslySaved) {
        this.availableTopics = availableTopics;
        // Initialize state with current database values
        this.selectedTopics = new HashSet<>(previouslySaved);
    }

    @NonNull
    @Override
    public TopicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTopicCheckboxBinding binding = ItemTopicCheckboxBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TopicViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TopicViewHolder holder, int position) {
        String topic = availableTopics.get(position);
        holder.bind(topic);
    }

    @Override
    public int getItemCount() {
        return availableTopics != null ? availableTopics.size() : 0;
    }

    /**
     * Helper method to retrieve the finalized list of selected topics.
     */
    public List<String> getSelectedTopics() {
        return new ArrayList<>(selectedTopics);
    }

    /**
     * ViewHolder class for the topic checkbox row.
     */
    class TopicViewHolder extends RecyclerView.ViewHolder {
        private final ItemTopicCheckboxBinding binding;

        public TopicViewHolder(ItemTopicCheckboxBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds topic name and sets up the toggle logic.
         */
        public void bind(String topic) {
            binding.tvTopicName.setText(topic);

            // Set checked state based on current selection set
            // We temporarily remove the listener to prevent recursion during binding
            binding.cbTopicSelection.setOnCheckedChangeListener(null);
            binding.cbTopicSelection.setChecked(selectedTopics.contains(topic));

            // Logic: Update the shared state set when checkbox is clicked
            binding.cbTopicSelection.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedTopics.add(topic);
                } else {
                    selectedTopics.remove(topic);
                }
            });

            // Make the whole row clickable for better mobile UX
            itemView.setOnClickListener(v -> binding.cbTopicSelection.toggle());
        }
    }
}