package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemRelayBinding;

import java.util.List;

/**
 * Adapter for the Advertiser's Relay Management List.
 * Binds relay URLs to UI items with status indicators and action buttons.
 */
public class RelayListAdapter extends RecyclerView.Adapter<RelayListAdapter.RelayViewHolder> {

    private final List<String> relayList;
    private final OnRelayClickListener listener;

    /**
     * Interface to communicate user actions back to the RelayManagerFragment.
     */
    public interface OnRelayClickListener {
        void onTestPing(String relayUrl);
        void onResellClicked(String relayUrl);
    }

    public RelayListAdapter(List<String> relayList, OnRelayClickListener listener) {
        this.relayList = relayList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RelayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRelayBinding binding = ItemRelayBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new RelayViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull RelayViewHolder holder, int position) {
        String relayUrl = relayList.get(position);
        holder.bind(relayUrl, listener);
    }

    @Override
    public int getItemCount() {
        return relayList != null ? relayList.size() : 0;
    }

    /**
     * ViewHolder for a single managed relay entry.
     */
    static class RelayViewHolder extends RecyclerView.ViewHolder {
        private final ItemRelayBinding binding;

        public RelayViewHolder(ItemRelayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds the relay URL to the UI and sets up click listeners.
         */
        public void bind(String relayUrl, OnRelayClickListener listener) {
            // 1. Display the Relay WebSocket URL
            binding.tvRelayUrl.setText(relayUrl);

            // 2. Set the connection status (mocked for UI)
            // In a live app, this would be updated by WebSocketClientManager
            boolean isOnline = Math.random() > 0.2; // 80% chance of being online
            if (isOnline) {
                binding.tvRelayStatus.setText("Online");
                binding.tvRelayStatus.setTextColor(itemView.getResources().getColor(android.R.color.holo_green_light));
            } else {
                binding.tvRelayStatus.setText("Offline");
                binding.tvRelayStatus.setTextColor(itemView.getResources().getColor(android.R.color.holo_red_light));
            }

            // 3. Setup Action Button: Test Ping
            binding.btnTestPing.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTestPing(relayUrl);
                }
            });

            // 4. Setup Action Button: Resell on Marketplace
            binding.btnResell.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onResellClicked(relayUrl);
                }
            });
        }
    }
}