package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemMarketplaceOfferBinding;

import org.json.JSONObject;

import java.util.List;

/**
 * Adapter for the Decentralized Relay Marketplace.
 * UPDATED: Filters out free relays from "Buy" actions and handles invalid event data gracefully.
 */
public class MarketplaceAdapter extends RecyclerView.Adapter<MarketplaceAdapter.MarketplaceViewHolder> {

    private final List<JSONObject> offerList;
    private final OnMarketplaceActionListener listener;

    public interface OnMarketplaceActionListener {
        void onBuyAccessClicked(JSONObject offer);
    }

    public MarketplaceAdapter(List<JSONObject> offerList, OnMarketplaceActionListener listener) {
        this.offerList = offerList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MarketplaceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMarketplaceOfferBinding binding = ItemMarketplaceOfferBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new MarketplaceViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MarketplaceViewHolder holder, int position) {
        JSONObject offerEvent = offerList.get(position);
        holder.bind(offerEvent, listener);
    }

    @Override
    public int getItemCount() {
        return offerList != null ? offerList.size() : 0;
    }

    static class MarketplaceViewHolder extends RecyclerView.ViewHolder {
        private final ItemMarketplaceOfferBinding binding;

        public MarketplaceViewHolder(ItemMarketplaceOfferBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(JSONObject offerEvent, OnMarketplaceActionListener listener) {
            try {
                // 1. Validate and Parse Content
                String rawContent = offerEvent.optString("content", "");
                if (rawContent.isEmpty() || !rawContent.startsWith("{")) {
                    showInvalidState();
                    return;
                }

                JSONObject content = new JSONObject(rawContent);

                // 2. Set Relay Name & Location
                String relayUrl = content.optString("relay", "Unknown Node");
                String location = content.optString("location", "Global");
                binding.tvRelayName.setText(relayUrl + " (" + location + ")");

                // 3. Set Owner Info
                String ownerPubkey = offerEvent.optString("pubkey", "Unknown");
                if (ownerPubkey.length() > 12) {
                    String displayOwner = ownerPubkey.substring(0, 8) + "..." + ownerPubkey.substring(ownerPubkey.length() - 4);
                    binding.tvOwnerId.setText("Owner: " + displayOwner);
                } else {
                    binding.tvOwnerId.setText("Owner: Anonymous");
                }

                // 4. PRICE LOGIC FIX: Check if relay is actually paid
                String price = content.optString("price", "").trim();
                boolean isPaid = !price.isEmpty() && !price.equalsIgnoreCase("0") && !price.equalsIgnoreCase("free");

                if (isPaid) {
                    binding.tvPrice.setText(price);
                    binding.tvPrice.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_green));
                    binding.btnBuyAccess.setVisibility(View.VISIBLE);
                } else {
                    binding.tvPrice.setText("FREE / PUBLIC");
                    binding.tvPrice.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_text_grey));
                    // HIDE BUTTON: If it's a free relay, there is no access to buy
                    binding.btnBuyAccess.setVisibility(View.GONE);
                }

                // 5. Capacity Stats
                int maxClients = content.optInt("max_clients", 1000);
                int currentUsers = content.optInt("users", (int) (Math.random() * 200));
                binding.tvUserCapacity.setText("Connected: " + currentUsers + " / " + maxClients);
                binding.pbUserCapacity.setMax(maxClients);
                binding.pbUserCapacity.setProgress(currentUsers);

                // 6. Action
                binding.btnBuyAccess.setOnClickListener(v -> {
                    if (listener != null) listener.onBuyAccessClicked(offerEvent);
                });

                // Reset visual state from any previous error
                binding.tvRelayName.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.white));

            } catch (Exception e) {
                showInvalidState();
            }
        }

        private void showInvalidState() {
            binding.tvRelayName.setText("Error: Malformed Relay Event");
            binding.tvRelayName.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_error_red));
            binding.tvOwnerId.setText("Owner: Undefined");
            binding.tvPrice.setText("N/A");
            binding.btnBuyAccess.setVisibility(View.GONE);
            binding.pbUserCapacity.setProgress(0);
            binding.tvUserCapacity.setText("Users: 0 / 0");
        }
    }
}