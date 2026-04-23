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
 * FIXED (Glitch 6): Enforced the "#t" tag requirement in the relay query filter to match
 * the broadcast protocol from CreateProductActivity, ensuring relays return the events.
 * FORENSIC UPDATE: Integrated RelayReportDialog for deep storefront diagnostics.
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

    // Forensic Log Accumulator
    private final StringBuilder storefrontLogs = new StringBuilder();

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
     * FIXED: Added the #t filter to ensure proper indexing and retrieval from relays.
     * UPDATED: Triggers Forensic Diagnostic Console.
     */
    private void fetchAdvertiserProducts() {
        storefrontLogs.setLength(0);
        logForensic("=== INITIATING STOREFRONT DISCOVERY ===");
        logForensic("TARGET_AUTHOR: " + targetPubkey);

        RelayReportDialog report = RelayReportDialog.newInstance(
                "STOREFRONT CONSOLE", 
                "Retrieving Catalog for " + businessName + "...", 
                storefrontLogs.toString()
        );
        report.showSafe(getSupportFragmentManager(), "STORE_LOG");

        binding.pbProfileLoading.setVisibility(View.VISIBLE);
        productList.clear();
        adapter.notifyDataSetChanged();

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30005));
            filter.put("authors", new JSONArray().put(targetPubkey));
            
            // CRITICAL FIX: Ensure we only fetch events tagged as marketplace_product
            filter.put("#t", new JSONArray().put("marketplace_product"));

            String subId = "store-" + UUID.randomUUID().toString().substring(0, 6);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            logForensic("REQ_OUT: " + req);

            wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
                @Override 
                public void onRelayConnected(String url) { 
                    logForensic("RELAY_TCP_OK: " + url + " - Deploying REQ filter.");
                    wsManager.subscribe(url, req); 
                }

                @Override public void onRelayDisconnected(String url, String reason) {
                    logForensic("RELAY_LOST: " + url + " (" + reason + ")");
                }

                @Override public void onError(String url, Exception ex) {
                    logForensic("SOCKET_ERR: " + url + " - " + ex.toString());
                }

                @Override
                public void onMessageReceived(String url, String message) {
                    runOnUiThread(() -> processStoreEvent(message));
                }
            });

            // FIXED: Using Pool management with automatic open-socket detection
            wsManager.connectPool(db.getRelayPool());

        } catch (Exception e) {
            logForensic("SETUP_ERR: " + e.getMessage());
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

                logForensic("POINTER_IN: Found item '" + title + "' hosted at CF R2.");

                // Prevent duplicates
                for (AdsPublisherFragment.ProductListing p : productList) {
                    if (p.jsonUrl.equals(jsonUrl)) {
                        logForensic("DUP_SKIP: Listing already in UI.");
                        return;
                    }
                }

                productList.add(new AdsPublisherFragment.ProductListing(title, price, jsonUrl));
                adapter.notifyItemInserted(productList.size() - 1);

            } else if ("EOSE".equals(type)) {
                logForensic("STATUS_EOSE: Relay search finished.");
                binding.pbProfileLoading.setVisibility(View.GONE);
                if (productList.isEmpty()) {
                    logForensic("RESULT_NULL: Author has no active 30005 events on this node.");
                    binding.tvNoStoreProducts.setVisibility(View.VISIBLE);
                }
            } else if ("NOTICE".equals(type)) {
                logForensic("NOTICE_FROM_RELAY: " + msg.optString(1));
            }

        } catch (Exception e) {
            logForensic("PARSE_FAIL: Discarded invalid JSON pointer.");
        }
    }

    private void logForensic(String msg) {
        storefrontLogs.append("[").append(System.currentTimeMillis()).append("] ").append(msg).append("\n");
        RelayReportDialog report = (RelayReportDialog) getSupportFragmentManager().findFragmentByTag("STORE_LOG");
        if (report != null) {
            report.updateTechnicalLogs("Forensic Store Scan", storefrontLogs.toString());
        }
        Log.d(TAG, msg);
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