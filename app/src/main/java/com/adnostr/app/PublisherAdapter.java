package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemPublisherProductBinding;

import java.util.List;

/**
 * Adapter for the Advertiser's Inventory Management.
 * FEATURE 5: Renders a list of marketplace products (Kind 30005).
 * Logic: Binds Metadata from Nostr Event -> Handles click to view Cloudflare JSON details.
 */
public class PublisherAdapter extends RecyclerView.Adapter<PublisherAdapter.PublisherViewHolder> {

    private final List<AdsPublisherFragment.ProductListing> products;
    private final OnProductClickListener listener;

    /**
     * Interface to communicate clicks back to the Publisher Fragment.
     */
    public interface OnProductClickListener {
        void onProductClicked(AdsPublisherFragment.ProductListing product);
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
        holder.bind(product, listener);
    }

    @Override
    public int getItemCount() {
        return products != null ? products.size() : 0;
    }

    /**
     * ViewHolder class for individual inventory product items.
     */
    static class PublisherViewHolder extends RecyclerView.ViewHolder {
        private final ItemPublisherProductBinding binding;

        public PublisherViewHolder(ItemPublisherProductBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds the product metadata to the view.
         */
        public void bind(AdsPublisherFragment.ProductListing product, OnProductClickListener listener) {
            // 1. Set Title and Price
            binding.tvProductTitle.setText(product.title);
            binding.tvProductPrice.setText("₹ " + product.price);

            // 2. Set Pointer Info (Technical ID/Link)
            String truncatedUrl = product.jsonUrl;
            if (truncatedUrl.length() > 20) {
                truncatedUrl = "..." + truncatedUrl.substring(truncatedUrl.length() - 15);
            }
            binding.tvProductPointer.setText("Pointer: " + truncatedUrl);

            // 3. Setup Click Navigation
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onProductClicked(product);
                }
            });
        }
    }
}