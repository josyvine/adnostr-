package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemPublisherProductBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the Advertiser's Inventory Management.
 * FEATURE 5: Renders a list of marketplace products (Kind 30005).
 * Logic: Binds Metadata from Nostr Event -> Handles click to view Cloudflare JSON details.
 * ENHANCEMENT: Added multi-selection logic for bulk deletion from R2 and Relays.
 */
public class PublisherAdapter extends RecyclerView.Adapter<PublisherAdapter.PublisherViewHolder> {

    private final List<AdsPublisherFragment.ProductListing> products;
    private final Set<Integer> selectedPositions = new HashSet<>();
    private final OnProductClickListener listener;

    /**
     * Interface to communicate clicks and selection state back to the Publisher Fragment.
     */
    public interface OnProductClickListener {
        void onProductClicked(AdsPublisherFragment.ProductListing product);
        void onSelectionChanged(int selectedCount);
    }

    public PublisherAdapter(List<AdsPublisherFragment.ProductListing> products, OnProductClickListener listener) {
        this.products = products;
        this.listener = listener;
    }

    @NonNull
    @Override
    public PublisherViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPublisherProductBinding binding = ItemPublisherProductBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PublisherViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PublisherViewHolder holder, int position) {
        AdsPublisherFragment.ProductListing product = products.get(position);
        boolean isSelected = selectedPositions.contains(position);
        holder.bind(product, position, isSelected, listener);
    }

    @Override
    public int getItemCount() {
        return products != null ? products.size() : 0;
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
     * Returns the list of currently selected products.
     */
    public List<AdsPublisherFragment.ProductListing> getSelectedItems() {
        List<AdsPublisherFragment.ProductListing> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            if (pos >= 0 && pos < products.size()) {
                selected.add(products.get(pos));
            }
        }
        return selected;
    }

    /**
     * Clears all selections and resets the UI.
     */
    public void clearSelection() {
        selectedPositions.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(0);
        }
    }

    /**
     * ViewHolder class for individual inventory product items.
     */
    class PublisherViewHolder extends RecyclerView.ViewHolder {
        private final ItemPublisherProductBinding binding;

        public PublisherViewHolder(ItemPublisherProductBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds the product metadata and handles selection UI states.
         */
        public void bind(AdsPublisherFragment.ProductListing product, int position, boolean isSelected, OnProductClickListener listener) {
            // 1. Set Title and Price
            binding.tvProductTitle.setText(product.title);
            binding.tvProductPrice.setText("₹ " + product.price);

            // 2. Set Pointer Info (Technical ID/Link)
            String truncatedUrl = product.jsonUrl;
            if (truncatedUrl != null && truncatedUrl.length() > 20) {
                truncatedUrl = "..." + truncatedUrl.substring(truncatedUrl.length() - 15);
            }
            binding.tvProductPointer.setText("Pointer: " + truncatedUrl);

            // 3. Update Selection Visuals
            if (isSelected) {
                binding.cvPublisherCard.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_blue));
                binding.cvPublisherCard.setStrokeWidth(6);
                binding.ivSelectionCheck.setVisibility(View.VISIBLE);
            } else {
                binding.cvPublisherCard.setStrokeColor(ContextCompat.getColor(itemView.getContext(), android.R.color.transparent));
                binding.cvPublisherCard.setStrokeWidth(0);
                binding.ivSelectionCheck.setVisibility(View.GONE);
            }

            // 4. Setup Click Listeners
            binding.cvPublisherCard.setOnClickListener(v -> {
                if (!selectedPositions.isEmpty()) {
                    // If in selection mode, toggle selection
                    toggleSelection(position);
                } else if (listener != null) {
                    // Normal mode: open details
                    listener.onProductClicked(product);
                }
            });

            // Long click to enter selection mode
            binding.cvPublisherCard.setOnLongClickListener(v -> {
                toggleSelection(position);
                return true;
            });
        }
    }
}