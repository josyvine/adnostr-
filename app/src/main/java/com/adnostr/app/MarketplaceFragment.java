package com.adnostr.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.FragmentMarketplaceBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decentralized Relay Marketplace.
 * UPDATED: Fixed background thread crash (Toasts) and added UI thread safety 
 * for network response processing.
 */
public class MarketplaceFragment extends Fragment implements MarketplaceAdapter.OnMarketplaceActionListener {

    private static final String TAG = "AdNostr_Marketplace";
    private FragmentMarketplaceBinding binding;

    private MarketplaceAdapter adapter;
    private List<JSONObject> marketplaceOffers;
    private WebSocketClientManager wsManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMarketplaceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        marketplaceOffers = new ArrayList<>();
        wsManager = WebSocketClientManager.getInstance();

        // 1. Setup UI
        setupRecyclerView();

        // 2. Fetch Relay Offers from the network
        fetchMarketplaceListings();

        // 3. Setup refresh listener
        binding.swipeRefreshLayout.setOnRefreshListener(this::fetchMarketplaceListings);
    }

    private void setupRecyclerView() {
        binding.rvMarketplace.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new MarketplaceAdapter(marketplaceOffers, this);
        binding.rvMarketplace.setAdapter(adapter);
    }

    /**
     * Connects to a bootstrap relay and requests all kind:30002 events.
     */
    private void fetchMarketplaceListings() {
        binding.swipeRefreshLayout.setRefreshing(true);
        marketplaceOffers.clear();
        adapter.notifyDataSetChanged();
        
        Log.i(TAG, "Requesting marketplace relay offers...");

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30002));

            String subId = "market-" + UUID.randomUUID().toString().substring(0, 4);
            String subscriptionMessage = new JSONArray()
                    .put("REQ")
                    .put(subId)
                    .put(filter)
                    .toString();

            // Set up the listener with UI Thread safety
            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override
                public void onRelayConnected(String url) {
                    wsManager.subscribe(url, subscriptionMessage);
                }
                
                @Override
                public void onMessageReceived(String url, String message) {
                    // All UI updates and parsing moved to UI thread
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> processMarketplaceEvent(message));
                    }
                }
                
                @Override
                public void onRelayDisconnected(String url, String reason) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> binding.swipeRefreshLayout.setRefreshing(false));
                    }
                }

                @Override
                public void onError(String url, Exception ex) {
                    // FIXED: UI Thread required for Toasting
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            binding.swipeRefreshLayout.setRefreshing(false);
                            Toast.makeText(getContext(), "Relay Connection Error", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            });

            // Trigger connection to a high-traffic bootstrap relay
            wsManager.connectRelay("wss://relay.damus.io");
            
        } catch (Exception e) {
            Log.e(TAG, "Marketplace subscription failed: " + e.getMessage());
            binding.swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void processMarketplaceEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            
            JSONArray msgArray = new JSONArray(rawMessage);
            String type = msgArray.getString(0);

            if ("EVENT".equals(type)) {
                JSONObject event = msgArray.getJSONObject(2);
                marketplaceOffers.add(event);
                adapter.notifyItemInserted(marketplaceOffers.size() - 1);
                binding.tvNoListings.setVisibility(View.GONE);
            } else if ("EOSE".equals(type)) {
                // End of stored events reached
                binding.swipeRefreshLayout.setRefreshing(false);
                if (marketplaceOffers.isEmpty()) {
                    binding.tvNoListings.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing marketplace message: " + e.getMessage());
        }
    }

    @Override
    public void onBuyAccessClicked(JSONObject offer) {
        try {
            JSONObject content = new JSONObject(offer.getString("content"));
            String paymentUrl = content.optString("payment_link", "");
            
            if (!paymentUrl.isEmpty() && paymentUrl.startsWith("http")) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl));
                startActivity(browserIntent);
            } else {
                Toast.makeText(getContext(), "This relay has no external payment link.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Cannot open purchase link.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // Clean up network listener to prevent memory leaks and background crashes
        wsManager.setStatusListener(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}