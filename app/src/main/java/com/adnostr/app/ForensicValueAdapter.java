package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NEW: Forensic Value Multi-Adapter.
 * - Role: Manages the vertical list of individual Bajaj models/years.
 * - Logic: Multi-selection tracking for surgical deletion.
 * - Style: High-contrast Moderator theme.
 */
public class ForensicValueAdapter extends RecyclerView.Adapter<ForensicValueAdapter.ValueViewHolder> {

    private final List<String> values;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private final OnSelectionChangeListener listener;

    /**
     * Interface to update the "Purge" button count in the Activity.
     */
    public interface OnSelectionChangeListener {
        void onSelectionChanged(int count);
    }

    public ForensicValueAdapter(List<String> values, OnSelectionChangeListener listener) {
        this.values = values;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ValueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_forensic_value, parent, false);
        return new ValueViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ValueViewHolder holder, int position) {
        String value = values.get(position);
        holder.bind(value, position);
    }

    @Override
    public int getItemCount() {
        return values != null ? values.size() : 0;
    }

    /**
     * Professional Logic: Flips the state of all items in the list.
     */
    public void selectAll(boolean shouldSelect) {
        selectedPositions.clear();
        if (shouldSelect) {
            for (int i = 0; i < getItemCount(); i++) {
                selectedPositions.add(i);
            }
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedPositions.size());
        }
    }

    /**
     * Returns the actual string list of models/years selected for removal.
     */
    public List<String> getSelectedValues() {
        List<String> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos >= 0 && pos < values.size()) {
                selected.add(values.get(pos));
            }
        }
        return selected;
    }

    class ValueViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvValueName;
        private final CheckBox cbValueSelect;

        public ValueViewHolder(@NonNull View itemView) {
            super(itemView);
            tvValueName = itemView.findViewById(R.id.tvValueName);
            cbValueSelect = itemView.findViewById(R.id.cbValueSelect);
        }

        public void bind(String value, int position) {
            tvValueName.setText(value);

            // Clean binding: remove listener before setting state to avoid recursion
            cbValueSelect.setOnCheckedChangeListener(null);
            cbValueSelect.setChecked(selectedPositions.contains(position));

            // Handle individual moderator selection
            cbValueSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedPositions.add(position);
                } else {
                    selectedPositions.remove(position);
                }
                
                if (listener != null) {
                    listener.onSelectionChanged(selectedPositions.size());
                }
            });

            // Allow clicking the text/row to toggle the checkbox
            itemView.setOnClickListener(v -> cbValueSelect.toggle());
        }
    }
}