package com.adnostr.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.adnostr.app.databinding.ActivityAdvertiserProfileBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FEATURE 5: Advertiser Marketplace Storefront.
 * Logic: Receives a Pubkey -> Queries Relays for that Author's Kind 30005 events.
 * Displays the product catalog for a specific business.
 */
public class AdvertiserProfileActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdvProfile";
    private ActivityAdvertiserProfileBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;

    private String targetPubkey;
    private String businessName;
    private final List<AdsPublisherFragment.ProductListing> productList = new ArrayList<>();
    private ProductBrowseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityAdvertiserProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        // 1. Extract intent data from the Directory click
        targetPubkey = getIntent().getStringExtra("ADVERTISER_PUBKEY");
        businessName = getIntent().getStringExtra("BUSINESS_NAME");

        if (targetPubkey == null || targetPubkey.isEmpty()) {
            finish();
            return;
        }

        // 2. Setup Toolbar
        setSupportActionBar(binding.toolbarAdvProfile);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(businessName != null ? businessName : "Storefront");
        }

        setupRecyclerView();

        // 3. Start fetching products for this author
        fetchAdvertiserProducts();
    }

    private void setupRecyclerView() {
        // Use a 2-column grid for a modern e-commerce "Storefront" look
        binding.rvAdvertiserProducts.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new ProductBrowseAdapter(productList, product -> {
            // Navigate to the Product Detail (Viewer)
            Intent intent = new Intent(this, ProductDetailActivity.class);
            intent.putExtra("PRODUCT_JSON_URL", product.jsonUrl);
            startActivity(intent);
        });
        binding.rvAdvertiserProducts.setAdapter(adapter);
    }

    /**
     * Subscribes to Kind 30005 events authored by the specific target advertiser.
     */
    private void fetchAdvertiserProducts() {
        binding.pbProfileLoading.setVisibility(View.VISIBLE);
        productList.clear();
        adapter.notifyDataSetChanged();

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30005));
            filter.put("authors", new JSONArray().put(targetPubkey));

            String subId = "store-" + UUID.randomUUID().toString().substring(0, 6);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override public void onRelayConnected(String url) { wsManager.subscribe(url, req); }
                @Override public void onRelayDisconnected(String url, String reason) {}
                @Override public void onError(String url, Exception ex) {}

                @Override
                public void onMessageReceived(String url, String message) {
                    runOnUiThread(() -> processStoreEvent(message));
                }
            });

            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            Log.e(TAG, "Storefront query failed: " + e.getMessage());
            binding.pbProfileLoading.setVisibility(View.GONE);
        }
    }

    private void processStoreEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            JSONArray msg = new JSONArray(rawMessage);
            String type = msg.getString(0);

            if ("EVENT".equals(type)) {
                JSONObject event = msg.getJSONObject(2);
                String content = event.getString("content");
                JSONObject meta = new JSONObject(content);

                String title = meta.optString("title", "Product");
                String price = meta.optString("price", "0");
                String jsonUrl = meta.optString("json_url", "");

                // Prevent duplicates
                for (AdsPublisherFragment.ProductListing p : productList) {
                    if (p.jsonUrl.equals(jsonUrl)) return;
                }

                productList.add(new AdsPublisherFragment.ProductListing(title, price, jsonUrl));
                adapter.notifyItemInserted(productList.size() - 1);

            } else if ("EOSE".equals(type)) {
                binding.pbProfileLoading.setVisibility(View.GONE);
                if (productList.isEmpty()) {
                    binding.tvNoStoreProducts.setVisibility(View.VISIBLE);
                }
            }

        } catch (Exception ignored) {}
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}