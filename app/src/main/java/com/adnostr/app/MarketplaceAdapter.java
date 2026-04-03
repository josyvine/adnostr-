package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemMarketplaceOfferBinding;

import org.json.JSONObject;

import java.util.List;

/**
 * Adapter for the Decentralized Relay Marketplace.
 * Binds kind:30002 Nostr events to the marketplace listing UI.
 */
public class MarketplaceAdapter extends RecyclerView.Adapter<MarketplaceAdapter.MarketplaceViewHolder> {

    private final List<JSONObject> offerList;
    private final OnMarketplaceActionListener listener;

    /**
     * Interface to handle actions from the marketplace list (e.g., buying).
     */
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

    /**
     * ViewHolder for a single marketplace relay offer.
     */
    static class MarketplaceViewHolder extends RecyclerView.ViewHolder {
        private final ItemMarketplaceOfferBinding binding;

        public MarketplaceViewHolder(ItemMarketplaceOfferBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Parses the Nostr event and binds its content to the UI views.
         */
        public void bind(JSONObject offerEvent, OnMarketplaceActionListener listener) {
            try {
                // 1. Extract the content of the event
                JSONObject content = new JSONObject(offerEvent.getString("content"));

                // 2. Set Basic Info
                String relayUrl = content.optString("relay", "Unknown Relay");
                String location = content.optString("location", "Global");
                String price = content.optString("price", "N/A");
                
                binding.tvRelayName.setText(relayUrl + " (" + location + ")");
                binding.tvPrice.setText(price);

                // 3. Set Owner Identity (Pubkey)
                String ownerPubkey = offerEvent.optString("pubkey", "Unknown Owner");
                String displayOwner = ownerPubkey.substring(0, 8) + "..." + ownerPubkey.substring(ownerPubkey.length() - 4);
                binding.tvOwnerId.setText("Owner: " + displayOwner);

                // 4. Set Capacity/Usage (Mocked for UI)
                int maxClients = content.optInt("max_clients", 1000);
                int currentUsers = (int) (Math.random() * maxClients);
                binding.tvUserCapacity.setText("Users: " + currentUsers + " / " + maxClients);
                binding.pbUserCapacity.setMax(maxClients);
                binding.pbUserCapacity.setProgress(currentUsers);

                // 5. Setup "Buy Access" Button
                binding.btnBuyAccess.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onBuyAccessClicked(offerEvent);
                    }
                });

            } catch (Exception e) {
                // Handle JSON parsing errors gracefully
                binding.tvRelayName.setText("Error: Invalid Offer Data");
                binding.tvPrice.setText("N/A");
            }
        }
    }
}