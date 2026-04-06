package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemMarketplaceOfferBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Adapter for the Decentralized Relay Marketplace.
 * UPDATED: Filters out free relays from "Buy" actions and handles invalid event data gracefully.
 * FIXED: Implemented fail-safe parsing for standard NIP-65 relay lists to prevent 
 * "Malformed Relay Event" errors and show free/public relays correctly.
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
                // 1. Identify the Relay URL and Pricing
                String rawContent = offerEvent.optString("content", "");
                String ownerPubkey = offerEvent.optString("pubkey", "Unknown");
                
                String relayUrl = "Unknown Node";
                String location = "Global";
                boolean isPaid = false;
                String priceText = "FREE / PUBLIC";

                // CASE A: Premium Relay Listing (Custom AdNostr JSON in content)
                if (!rawContent.isEmpty() && rawContent.startsWith("{")) {
                    JSONObject content = new JSONObject(rawContent);
                    relayUrl = content.optString("relay", "Unknown Node");
                    location = content.optString("location", "Global");
                    
                    String price = content.optString("price", "").trim();
                    isPaid = !price.isEmpty() && !price.equalsIgnoreCase("0") && !price.equalsIgnoreCase("free");
                    
                    if (isPaid) {
                        priceText = price;
                    }
                } 
                // CASE B: Standard Public Relay (NIP-65 / Kind 10002/30002 Tags)
                else {
                    JSONArray tags = offerEvent.optJSONArray("tags");
                    if (tags != null) {
                        for (int i = 0; i < tags.length(); i++) {
                            JSONArray tagPair = tags.optJSONArray(i);
                            if (tagPair != null && tagPair.length() >= 2) {
                                String tagName = tagPair.optString(0);
                                if ("r".equals(tagName)) {
                                    relayUrl = tagPair.optString(1);
                                    break;
                                }
                            }
                        }
                    }
                }

                // 2. Set UI Text Fields
                binding.tvRelayName.setText(relayUrl + " (" + location + ")");

                // Set Owner Info (Truncated)
                if (ownerPubkey.length() > 12) {
                    String displayOwner = ownerPubkey.substring(0, 8) + "..." + ownerPubkey.substring(ownerPubkey.length() - 4);
                    binding.tvOwnerId.setText("Owner: " + displayOwner);
                } else {
                    binding.tvOwnerId.setText("Owner: " + ownerPubkey);
                }

                // 3. Price and Action Logic
                if (isPaid) {
                    binding.tvPrice.setText(priceText);
                    binding.tvPrice.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_active_green));
                    binding.btnBuyAccess.setVisibility(View.VISIBLE);
                } else {
                    binding.tvPrice.setText("FREE / PUBLIC");
                    binding.tvPrice.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.hfs_text_grey));
                    binding.btnBuyAccess.setVisibility(View.GONE);
                }

                // 4. Capacity Stats (Randomized for visual simulation if not in JSON)
                int maxClients = 1000;
                int currentUsers = (int) (Math.random() * 200);
                
                // If JSON provides real stats, use them
                if (!rawContent.isEmpty() && rawContent.startsWith("{")) {
                    JSONObject content = new JSONObject(rawContent);
                    maxClients = content.optInt("max_clients", 1000);
                    currentUsers = content.optInt("users", currentUsers);
                }

                binding.tvUserCapacity.setText("Connected: " + currentUsers + " / " + maxClients);
                binding.pbUserCapacity.setMax(maxClients);
                binding.pbUserCapacity.setProgress(currentUsers);

                // 5. Setup Action Click
                binding.btnBuyAccess.setOnClickListener(v -> {
                    if (listener != null) listener.onBuyAccessClicked(offerEvent);
                });

                // Reset visual state
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