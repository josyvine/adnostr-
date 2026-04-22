package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemBrowseProductBinding;

import java.util.List;

/**
 * Adapter for the User Marketplace Discovery Grid.
 * FEATURE 5: Renders a 2-column gallery of product pointers (Kind 30005).
 * Logic: Maps lightweight Nostr metadata to cards -> Triggers full JSON detail view.
 */
public class ProductBrowseAdapter extends RecyclerView.Adapter<ProductBrowseAdapter.BrowseViewHolder> {

    private final List<AdsPublisherFragment.ProductListing> products;
    private final OnProductClickListener listener;

    /**
     * Interface to handle product selection and opening the detailed WebView viewer.
     */
    public interface OnProductClickListener {
        void onProductClicked(AdsPublisherFragment.ProductListing product);
    }

    public ProductBrowseAdapter(List<AdsPublisherFragment.ProductListing> products, OnProductClickListener listener) {
        this.products = products;
        this.listener = listener;
    }

    @NonNull
    @Override
    public BrowseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBrowseProductBinding binding = ItemBrowseProductBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new BrowseViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull BrowseViewHolder holder, int position) {
        holder.bind(products.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return products != null ? products.size() : 0;
    }

    /**
     * ViewHolder for the marketplace grid item.
     */
    static class BrowseViewHolder extends RecyclerView.ViewHolder {
        private final ItemBrowseProductBinding binding;

        public BrowseViewHolder(ItemBrowseProductBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds the lightweight metadata to the UI.
         */
        public void bind(AdsPublisherFragment.ProductListing product, OnProductClickListener listener) {
            // 1. Set the Title and Price gossiped in the Kind 30005 content
            binding.tvBrowseTitle.setText(product.title);
            binding.tvBrowsePrice.setText("₹ " + product.price);

            // 2. Setup Click Navigation to the Detail Activity
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onProductClicked(product);
                }
            });
        }
    }
}