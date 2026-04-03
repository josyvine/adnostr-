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
 * Fetches and displays kind:30002 (Relay Offer) events from the Nostr network,
 * allowing advertisers to buy premium relay access from other peers.
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
        
        Log.i(TAG, "Fetching latest relay marketplace listings...");

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30002)); // Relay Offer events

            String subscriptionMessage = new JSONArray()
                    .put("REQ")
                    .put(UUID.randomUUID().toString())
                    .put(filter)
                    .toString();

            // Set up a temporary listener for the response
            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override
                public void onRelayConnected(String url) {
                    wsManager.subscribe(url, subscriptionMessage);
                }
                
                @Override
                public void onMessageReceived(String url, String message) {
                    processMarketplaceEvent(message);
                }
                
                @Override
                public void onRelayDisconnected(String url, String reason) {
                    binding.swipeRefreshLayout.setRefreshing(false);
                }

                @Override
                public void onError(String url, Exception ex) {
                    binding.swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(getContext(), "Network Error", Toast.LENGTH_SHORT).show();
                }
            });

            // Connect to a public relay to fetch the data
            wsManager.connectRelay("wss://relay.damus.io");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create marketplace request: " + e.getMessage());
            binding.swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void processMarketplaceEvent(String rawMessage) {
        try {
            JSONArray msgArray = new JSONArray(rawMessage);
            if ("EVENT".equals(msgArray.getString(0))) {
                JSONObject event = msgArray.getJSONObject(2);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        marketplaceOffers.add(event);
                        adapter.notifyItemInserted(marketplaceOffers.size() - 1);
                        binding.tvNoListings.setVisibility(View.GONE);
                    });
                }
            } else if ("EOSE".equals(msgArray.getString(0))) {
                // End Of Stored Events - stop refreshing indicator
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> binding.swipeRefreshLayout.setRefreshing(false));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing marketplace event", e);
        }
    }

    /**
     * Handles the "BUY ACCESS" button click from the adapter.
     * Opens the relay owner's payment link in an external browser.
     */
    @Override
    public void onBuyAccessClicked(JSONObject offer) {
        try {
            JSONObject content = new JSONObject(offer.getString("content"));
            String paymentUrl = content.optString("payment_link", "");
            
            if (paymentUrl.startsWith("http")) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl));
                startActivity(browserIntent);
            } else {
                Toast.makeText(getContext(), "No valid payment link provided by owner.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Invalid offer data.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // Disconnect the temporary listener when leaving the screen
        wsManager.disconnectRelay("wss://relay.damus.io");
        wsManager.setStatusListener(null);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}