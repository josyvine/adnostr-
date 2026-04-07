package com.adnostr.app;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemAdHistoryBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import coil.Coil;
import coil.request.ImageRequest;

/**
 * Adapter for the Ads History List.
 * FEATURE: Renders history cards with thumbnails, titles, and timestamps.
 * FEATURE: Supports multi-selection for bulk deletion.
 * FEATURE: Handles both single-string and JSONArray image payloads from IPFS.
 */
public class AdHistoryAdapter extends RecyclerView.Adapter<AdHistoryAdapter.AdHistoryViewHolder> {

    private static final String TAG = "AdNostr_HistoryAdapter";
    private final List<String> adJsonList;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private final OnAdHistoryClickListener listener;

    /**
     * Interface to communicate clicks and selection status to the fragment.
     */
    public interface OnAdHistoryClickListener {
        void onAdTapped(String adJson);
        void onSelectionChanged(int selectedCount);
    }

    public AdHistoryAdapter(List<String> adJsonList, OnAdHistoryClickListener listener) {
        this.adJsonList = adJsonList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AdHistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAdHistoryBinding binding = ItemAdHistoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AdHistoryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AdHistoryViewHolder holder, int position) {
        String fullPayload = adJsonList.get(position);
        boolean isSelected = selectedPositions.contains(position);
        holder.bind(fullPayload, position, isSelected);
    }

    @Override
    public int getItemCount() {
        return adJsonList != null ? adJsonList.size() : 0;
    }

    /**
     * Toggles the selection of an item at a specific position.
     */
    private void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);
        if (listener != null) {
            listener.onSelectionChanged(selectedPositions.size());
        }
    }

    /**
     * Returns the full JSON payloads of all currently selected ads.
     */
    public List<String> getSelectedItems() {
        List<String> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            selected.add(adJsonList.get(pos));
        }
        return selected;
    }

    /**
     * Clears all selections.
     */
    public void clearSelection() {
        selectedPositions.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(0);
        }
    }

    /**
     * ViewHolder for an individual Ad History card.
     */
    class AdHistoryViewHolder extends RecyclerView.ViewHolder {
        private final ItemAdHistoryBinding binding;

        public AdHistoryViewHolder(ItemAdHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds data and handles selection UI states.
         */
        public void bind(String fullPayload, int position, boolean isSelected) {
            try {
                // 1. Parse the full Nostr message array ["EVENT", subId, {event}]
                JSONArray msgArray = new JSONArray(fullPayload);
                JSONObject eventObj = msgArray.getJSONObject(2);
                
                // 2. Extract content JSON
                String contentStr = eventObj.getString("content");
                JSONObject content = new JSONObject(contentStr);

                // 3. Set Title and Timestamp
                binding.tvHistoryAdTitle.setText(content.optString("title", "Untitled Ad"));
                
                long createdAt = eventObj.optLong("created_at", 0) * 1000;
                String dateStr = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                        .format(new Date(createdAt));
                binding.tvHistoryAdDate.setText(dateStr);

                // 4. Handle Thumbnail (First image from JSONArray or single String)
                String thumbnailUrl = "";
                Object imageObj = content.opt("image");
                if (imageObj instanceof JSONArray) {
                    JSONArray imgArray = (JSONArray) imageObj;
                    if (imgArray.length() > 0) {
                        thumbnailUrl = imgArray.getString(0);
                    }
                } else if (imageObj instanceof String) {
                    thumbnailUrl = (String) imageObj;
                }

                if (!thumbnailUrl.isEmpty()) {
                    loadThumbnail(thumbnailUrl);
                } else {
                    binding.ivHistoryThumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                }

                // 5. Update Selection Visuals
                if (isSelected) {
                    binding.cvHistoryCard.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_blue));
                    binding.cvHistoryCard.setStrokeWidth(6);
                    binding.ivSelectionCheck.setVisibility(View.VISIBLE);
                } else {
                    binding.cvHistoryCard.setStrokeColor(ContextCompat.getColor(itemView.getContext(), android.R.color.transparent));
                    binding.cvHistoryCard.setStrokeWidth(0);
                    binding.ivSelectionCheck.setVisibility(View.GONE);
                }

                // 6. Setup Click Listeners
                binding.cvHistoryCard.setOnClickListener(v -> {
                    if (!selectedPositions.isEmpty()) {
                        toggleSelection(position);
                    } else if (listener != null) {
                        listener.onAdTapped(fullPayload);
                    }
                });

                binding.cvHistoryCard.setOnLongClickListener(v -> {
                    toggleSelection(position);
                    return true;
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to bind history item: " + e.getMessage());
                binding.tvHistoryAdTitle.setText("Corrupted Ad Data");
            }
        }

        /**
         * Resolves IPFS protocol and loads the image into the view using Coil.
         */
        private void loadThumbnail(String url) {
            String resolvedUrl = url.replace("ipfs://", "https://cloudflare-ipfs.com/ipfs/");
            ImageRequest request = new ImageRequest.Builder(itemView.getContext())
                    .data(resolvedUrl)
                    .crossfade(true)
                    .placeholder(android.R.drawable.progress_indeterminate_horizontal)
                    .target(binding.ivHistoryThumbnail)
                    .build();
            Coil.imageLoader(itemView.getContext()).enqueue(request);
        }
    }
}