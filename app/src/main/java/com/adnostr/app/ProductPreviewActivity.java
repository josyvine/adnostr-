package com.adnostr.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityProductPreviewBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * FEATURE 5: Product Preview & Forensic Publisher.
 * Logic: Receives JSON -> Renders in Viewer -> Confirms -> Uploads JSON -> Broadcasts Kind 30005.
 * FIXED: Implements Forensic Log reporting during the network phase.
 */
public class ProductPreviewActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Preview";
    private ActivityProductPreviewBinding binding;
    private AdNostrDatabaseHelper db;
    private CloudflareHelper cloudHelper;
    private WebSocketClientManager wsManager;

    private String productJsonString;
    private boolean isPageLoaded = false;
    private final StringBuilder forensicLogs = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProductPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        cloudHelper = new CloudflareHelper();
        wsManager = WebSocketClientManager.getInstance();

        // 1. Get raw product data from Intent
        productJsonString = getIntent().getStringExtra("PRODUCT_JSON");

        if (productJsonString == null || productJsonString.isEmpty()) {
            Toast.makeText(this, "No data to preview.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();

        // 2. Navigation Listeners
        binding.btnEditMore.setOnClickListener(v -> finish());

        binding.btnFinalPublish.setOnClickListener(v -> performForensicPublish());

        binding.btnCloseForensic.setOnClickListener(v -> binding.flForensicOverlay.setVisibility(View.GONE));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = binding.wvProductPreview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        binding.wvProductPreview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                injectDataToViewer();
            }
        });

        binding.wvProductPreview.loadUrl("file:///android_asset/lox_viewer.html");
    }

    private void injectDataToViewer() {
        if (!isPageLoaded) return;
        // Escape backslashes for JS injection
        String escapedJson = productJsonString.replace("\\", "\\\\").replace("'", "\\'");
        binding.wvProductPreview.evaluateJavascript("renderProduct('" + escapedJson + "')", null);
    }

    /**
     * Executes the final publish flow with deep technical logging.
     */
    private void performForensicPublish() {
        forensicLogs.setLength(0);
        forensicLogs.append("=== INITIATING FINAL BROADCAST SEQUENCE ===\n");
        forensicLogs.append("TIMESTAMP: ").append(System.currentTimeMillis()).append("\n\n");

        RelayReportDialog console = RelayReportDialog.newInstance(
                "PUBLISH CONSOLE", 
                "Uploading JSON to Private Cloud...", 
                forensicLogs.toString()
        );
        console.showSafe(getSupportFragmentManager(), "PUBLISH_LOG");

        // STEP 1: Upload heavy JSON to Cloudflare
        String fileName = "product_" + System.currentTimeMillis() + ".json";
        
        forensicLogs.append("[STEP 1] REQUEST: POST -> Cloudflare R2\n");
        forensicLogs.append("PAYLOAD SIZE: ").append(productJsonString.length()).append(" chars\n");
        console.updateTechnicalLogs("Uploading to R2...", forensicLogs.toString());

        cloudHelper.uploadJsonFile(this, productJsonString, fileName, new CloudflareHelper.CloudflareCallback() {
            @Override
            public void onStatusUpdate(String log) {
                forensicLogs.append("[R2_TRACE] ").append(log).append("\n");
                runOnUiThread(() -> console.updateTechnicalLogs("Cloudflare Link Active", forensicLogs.toString()));
            }

            @Override
            public void onSuccess(String uploadedUrl, String fileId) {
                forensicLogs.append("\n[SUCCESS] CLOUDFLARE LINK: ").append(uploadedUrl).append("\n");
                forensicLogs.append("FILE_ID: ").append(fileId).append("\n\n");
                
                // STEP 2: Sign and Broadcast Kind 30005 Pointer
                broadcastMarketplacePointer(uploadedUrl, console);
            }

            @Override
            public void onFailure(Exception e) {
                forensicLogs.append("\n!!! CRITICAL FAILURE (CLOUDFLARE) !!!\n");
                forensicLogs.append("ERROR_MSG: ").append(e.getMessage()).append("\n");
                runOnUiThread(() -> console.updateTechnicalLogs("UPLOAD FAILED", forensicLogs.toString()));
            }
        });
    }

    private void broadcastMarketplacePointer(String cloudflareUrl, RelayReportDialog console) {
        try {
            JSONObject originalData = new JSONObject(productJsonString);
            
            JSONObject pointerContent = new JSONObject();
            pointerContent.put("title", originalData.optString("title", "Untitled"));
            pointerContent.put("price", originalData.optString("price", "0"));
            pointerContent.put("json_url", cloudflareUrl);

            JSONObject event = new JSONObject();
            event.put("kind", 30005);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", pointerContent.toString());

            JSONArray tags = new JSONArray();
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_listing_" + UUID.randomUUID().toString().substring(0, 8));
            tags.put(dTag);
            
            JSONArray tTag = new JSONArray();
            tTag.put("t");
            tTag.put("marketplace_product");
            tags.put(tTag);
            
            event.put("tags", tags);

            forensicLogs.append("[STEP 2] BIP-340 CRYPTO SIGNING...\n");
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);

            if (signedEvent != null) {
                forensicLogs.append("EVENT_ID: ").append(signedEvent.getString("id")).append("\n");
                forensicLogs.append("SIGNATURE: ").append(signedEvent.getString("sig")).append("\n\n");
                
                forensicLogs.append("[STEP 3] RELAY POOL BROADCAST\n");
                String rawProtocolFrame = "[\"EVENT\"," + signedEvent.toString() + "]";
                forensicLogs.append("RAW_FRAME: ").append(rawProtocolFrame).append("\n");
                
                runOnUiThread(() -> console.updateTechnicalLogs("Broadcasting to Relays...", forensicLogs.toString()));

                wsManager.broadcastEvent(signedEvent.toString());
                
                forensicLogs.append("\n[FINAL SUCCESS] Marketplace Pointer Live.");
                runOnUiThread(() -> {
                    console.updateTechnicalLogs("PUBLISHED SUCCESSFULLY", forensicLogs.toString());
                    Toast.makeText(this, "Listing Live on Network!", Toast.LENGTH_LONG).show();
                    // Close preview and return to dashboard
                    finish();
                });
            } else {
                throw new Exception("Schnorr Signing Failed.");
            }

        } catch (Exception e) {
            forensicLogs.append("\n!!! BROADCAST FAILED !!!\n").append(e.getMessage());
            runOnUiThread(() -> console.updateTechnicalLogs("PROTOCOL ERROR", forensicLogs.toString()));
        }
    }
}