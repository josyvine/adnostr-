package com.adnostr.app;

import android.content.Intent;
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

import com.adnostr.app.databinding.FragmentAdsPublisherBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FEATURE 5: Advertiser Product Inventory Dashboard.
 * Logic: Fetches Kind 30005 (Marketplace Pointer) events authored by this user.
 * Displays a list of active products and provides the entry point to create new ones.
 * ENHANCEMENT: Added bulk deletion functionality for both Nostr Events and Cloudflare R2 storage.
 */
public class AdsPublisherFragment extends Fragment implements PublisherAdapter.OnProductClickListener {

    private static final String TAG = "AdNostr_Publisher";
    private FragmentAdsPublisherBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    private CloudflareHelper cloudHelper;

    private final List<ProductListing> myProducts = new ArrayList<>();
    private PublisherAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAdsPublisherBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        wsManager = WebSocketClientManager.getInstance();
        cloudHelper = new CloudflareHelper();

        setupRecyclerView();

        // FAB: Open the WebView-based Product Creator
        binding.fabCreateProduct.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CreateProductActivity.class);
            startActivity(intent);
        });

        // ENHANCEMENT: Delete FAB Logic
        binding.fabDeleteSelected.setOnClickListener(v -> processBulkDeletion());

        binding.swipeRefreshPublisher.setOnRefreshListener(this::fetchMyProducts);

        // Initial fetch
        fetchMyProducts();
    }

    private void setupRecyclerView() {
        binding.rvMyProducts.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Link this fragment as the listener for clicks and selection changes
        adapter = new PublisherAdapter(myProducts, this);
        binding.rvMyProducts.setAdapter(adapter);
    }

    /**
     * Subscribes to Kind 30005 events where the author is the current advertiser.
     */
    private void fetchMyProducts() {
        binding.swipeRefreshPublisher.setRefreshing(true);
        myProducts.clear();
        adapter.notifyDataSetChanged();

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30005));
            filter.put("authors", new JSONArray().put(db.getPublicKey()));

            String subId = "mypub-" + UUID.randomUUID().toString().substring(0, 4);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override public void onRelayConnected(String url) { wsManager.subscribe(url, req); }
                @Override public void onRelayDisconnected(String url, String reason) {}
                @Override public void onError(String url, Exception ex) {}

                @Override
                public void onMessageReceived(String url, String message) {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> processProductEvent(message));
                    }
                }
            });

            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            Log.e(TAG, "Publisher fetch error: " + e.getMessage());
            binding.swipeRefreshPublisher.setRefreshing(false);
        }
    }

    private void processProductEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            JSONArray msg = new JSONArray(rawMessage);
            String type = msg.getString(0);

            if ("EVENT".equals(type)) {
                JSONObject event = msg.getJSONObject(2);
                String eventId = event.getString("id"); // Extract Event ID for Kind 5 deletion
                String content = event.getString("content");
                JSONObject meta = new JSONObject(content);

                String title = meta.optString("title", "Untitled Product");
                String price = meta.optString("price", "0");
                String jsonUrl = meta.optString("json_url", "");

                // Filter duplicates by d-tag or event ID
                for (ProductListing p : myProducts) {
                    if (p.jsonUrl.equals(jsonUrl)) return;
                }

                // Updated to include eventId in the model
                myProducts.add(new ProductListing(title, price, jsonUrl, eventId));
                adapter.notifyItemInserted(myProducts.size() - 1);

            } else if ("EOSE".equals(type)) {
                binding.swipeRefreshPublisher.setRefreshing(false);
                updateEmptyState();
            }

        } catch (Exception ignored) {}
    }

    /**
     * Core logic for single-click bulk deletion from Cloudflare and Nostr.
     */
    private void processBulkDeletion() {
        List<ProductListing> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        Toast.makeText(getContext(), "Wiping " + selected.size() + " listings...", Toast.LENGTH_SHORT).show();

        try {
            for (ProductListing product : selected) {
                // PART 1: Physical Wipe from Cloudflare R2
                // CloudflareHelper extracts the ID from the full URL automatically
                cloudHelper.deleteMedia(requireContext(), product.jsonUrl, null);

                // PART 2: Broadcast Nostr Kind 5 (Event Deletion)
                JSONObject deletionEvent = new JSONObject();
                deletionEvent.put("kind", 5);
                deletionEvent.put("pubkey", db.getPublicKey());
                deletionEvent.put("created_at", System.currentTimeMillis() / 1000);
                deletionEvent.put("content", "Listing removed by seller.");

                JSONArray tags = new JSONArray();
                JSONArray eTag = new JSONArray();
                eTag.put("e");
                eTag.put(product.eventId);
                tags.put(eTag);
                deletionEvent.put("tags", tags);

                JSONObject signedDeletion = NostrEventSigner.signEvent(db.getPrivateKey(), deletionEvent);
                if (signedDeletion != null) {
                    wsManager.broadcastEvent(signedDeletion.toString());
                }

                // Remove from local UI list
                myProducts.remove(product);
            }

            adapter.clearSelection();
            adapter.notifyDataSetChanged();
            updateEmptyState();
            Toast.makeText(getContext(), "Products deleted successfully.", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Deletion failed: " + e.getMessage());
        }
    }

    /**
     * Interface: Normal tap to view details.
     */
    @Override
    public void onProductClicked(AdsPublisherFragment.ProductListing product) {
        Intent intent = new Intent(requireContext(), ProductDetailActivity.class);
        intent.putExtra("PRODUCT_JSON_URL", product.jsonUrl);
        startActivity(intent);
    }

    /**
     * Interface: Shows or hides the Delete FAB based on selection count.
     */
    @Override
    public void onSelectionChanged(int selectedCount) {
        if (selectedCount > 0) {
            binding.fabDeleteSelected.show();
        } else {
            binding.fabDeleteSelected.hide();
        }
    }

    private void updateEmptyState() {
        if (myProducts.isEmpty()) {
            binding.llEmptyPublisher.setVisibility(View.VISIBLE);
        } else {
            binding.llEmptyPublisher.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Data model for marketplace pointers.
     * UPDATED: Now includes eventId for Nostr Kind 5 support.
     */
    public static class ProductListing {
        String title, price, jsonUrl, eventId;
        ProductListing(String t, String p, String u, String id) {
            this.title = t; this.price = p; this.jsonUrl = u; this.eventId = id;
        }
    }
}