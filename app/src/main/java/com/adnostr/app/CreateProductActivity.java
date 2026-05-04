package com.adnostr.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityCreateProductBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * FEATURE 5: Product Creator Activity.
 * FIXED: Image pipeline now uploads to R2 and pushes URLs back to HTML.
 * FIXED: Detailed Forensic Logging integrated via logTechnicalEvent bridge.
 * FIXED: Implements Preview logic to staging viewer before broadcast.
 * UPDATED: Extracts Category string for lightweight Marketplace Storefront rendering.
 * ENHANCEMENT: Implements Global Crowdsourced Schema Sync and Broadcasting.
 * UPDATED: Integrated Deletion Persistence logic into the WebAppInterface bridge.
 * ENHANCEMENT: Product publishing logs respect Global Console Visibility and Debug/Professional modes.
 * 
 * COLLECTIVE MEMORY UPDATE:
 * - triggerAutomaticHealing: Background bridge that re-signs and re-broadcasts local schema to heal amnesic relays.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Status Injection: Automatically informs the HTML engine if the user has Admin privileges.
 * - Deletion Unlocking: Enables the management of hardcoded and crowdsourced categories via the JS bridge.
 * 
 * UI AUTO-REFRESH FIX (NEW):
 * - SchemaEventListener: The Activity now monitors the network for 30006/30007 events.
 * - Auto-Injection: As tiers finish re-publishing, the WebView dropdowns are refreshed 
 *   automatically without requiring an activity restart.
 * - REPAIR UPDATE: onSchemaEventReceived now re-fetches merged archive data for real-time UI population.
 * - RETRIEVAL ENGINE: Added native bridge methods to allow the HTML engine to "Pull" crowdsourced data on-demand from the forensic archive.
 */
public class CreateProductActivity extends AppCompatActivity implements WebSocketClientManager.SchemaEventListener {

    private static final String TAG = "AdNostr_CreateProduct";
    private ActivityCreateProductBinding binding;
    private AdNostrDatabaseHelper db;
    private CloudflareHelper cloudHelper;
    private WebSocketClientManager wsManager;

    private ValueCallback<Uri[]> uploadMessage;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    // Forensic log accumulator
    private final StringBuilder technicalConsole = new StringBuilder();

    // Global Schema State
    private String fetchedGlobalSchemaJson = "{}";
    private boolean isWebViewReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCreateProductBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        cloudHelper = new CloudflareHelper();
        wsManager = WebSocketClientManager.getInstance();

        // UI AUTO-REFRESH: Register this activity to listen for real-time schema updates
        wsManager.addSchemaListener(this);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // FIX: Instead of just returning URIs to WebView, Android handles the upload
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            for (int i = 0; i < count; i++) {
                                handleNativeImageUpload(result.getData().getClipData().getItemAt(i).getUri());
                            }
                        } else if (result.getData().getData() != null) {
                            handleNativeImageUpload(result.getData().getData());
                        }
                    }
                    if (uploadMessage != null) {
                        uploadMessage.onReceiveValue(null); // Reset standard picker
                        uploadMessage = null;
                    }
                }
        );

        // INITIATE GLOBAL SCHEMA FETCH (Background Thread)
        // Initial load pulls from Cache/Forensic Archive immediately
        MarketplaceSchemaManager.fetchGlobalSchema(this, schemaJson -> {
            fetchedGlobalSchemaJson = schemaJson;
            logTechnicalEvent("SCHEMA: Global JSON downloaded from forensic archive. Length: " + schemaJson.length());
            injectSchemaIfReady();
        });

        setupWebView();
        binding.btnBackCreator.setOnClickListener(v -> finish());
    }

    /**
     * UI AUTO-REFRESH: Implementation of SchemaEventListener.
     * Logic: If a re-published tier (Bajaj/Audi) is detected on the wire, 
     * re-trigger the schema merge and inject it into the WebView instantly.
     * REPAIR UPDATE: Triggers immediate fetch of merged forensic archive data.
     */
    @Override
    public void onSchemaEventReceived(String url, JSONObject event) {
        runOnUiThread(() -> {
            // Re-fetch with the newly arrived metadata merged from the Hard-Locked Archive
            MarketplaceSchemaManager.fetchGlobalSchema(this, schemaJson -> {
                fetchedGlobalSchemaJson = schemaJson;
                // Zero-Refresh Injection: Repopulate dropdowns in real-time
                injectSchemaIfReady();
                logTechnicalEvent("UI_SYNC: Dropdowns refreshed with live data from " + url);
            });
        });
    }

    /**
     * Injects the heavy schema into the HTML safely using Base64
     * to prevent crashes from complex user-generated strings.
     */
    private void injectSchemaIfReady() {
        if (isWebViewReady && !fetchedGlobalSchemaJson.equals("{}")) {
            runOnUiThread(() -> {
                try {
                    // Normalize the JSON string to ensure no illegal characters break the bridge
                    String sanitizedJson = fetchedGlobalSchemaJson;
                    String base64Encoded = Base64.encodeToString(sanitizedJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

                    // The decodeURIComponent(escape(window.atob(...))) sequence handles multi-byte UTF-8 (emojis/special chars)
                    String jsCommand = "injectGlobalSchema(decodeURIComponent(escape(window.atob('" + base64Encoded + "'))))";
                    binding.wvProductCreator.evaluateJavascript(jsCommand, null);
                    logTechnicalEvent("SCHEMA: Injected de-duplicated archive into HTML engine.");
                } catch (Exception e) {
                    logTechnicalEvent("SCHEMA_ERROR: Failed to inject base64. " + e.getMessage());
                }
            });
        }
    }

    /**
     * Reads image bytes, uploads to Cloudflare, and injects URL back into HTML grid.
     */
    private void handleNativeImageUpload(Uri uri) {
        try {
            logTechnicalEvent("PICKER: Selected URI " + uri.toString());
            InputStream iStream = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = iStream.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            byte[] rawBytes = byteBuffer.toByteArray();

            logTechnicalEvent("R2: Uploading binary blob (" + rawBytes.length + " bytes)...");

            cloudHelper.uploadMedia(this, rawBytes, "prod_" + System.currentTimeMillis() + ".jpg", new CloudflareHelper.CloudflareCallback() {
                @Override public void onStatusUpdate(String log) { logTechnicalEvent("R2_TRACE: " + log); }

                @Override
                public void onSuccess(String uploadedUrl, String fileId) {
                    runOnUiThread(() -> {
                        logTechnicalEvent("R2_SUCCESS: " + uploadedUrl);
                        // Push URL back into the HTML JavaScript function
                        binding.wvProductCreator.evaluateJavascript("addUploadedImageUrl('" + uploadedUrl + "')", null);
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> logTechnicalEvent("R2_ERROR: " + e.getMessage()));
                }
            });
        } catch (Exception e) {
            logTechnicalEvent("IO_ERROR: " + e.getMessage());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = binding.wvProductCreator.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        binding.wvProductCreator.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        binding.wvProductCreator.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isWebViewReady = true;
                logTechnicalEvent("WEBVIEW: Dashboard DOM Loaded.");

                // ADMIN SUPREMACY: Inject status to toggle UI management tools
                boolean isAdmin = db.isAdmin();
                binding.wvProductCreator.evaluateJavascript("if(window.injectAdminStatus) injectAdminStatus(" + isAdmin + ");", null);
                if(isAdmin) logTechnicalEvent("ADMIN: Supremacy authenticated in WebView.");

                injectSchemaIfReady();
            }
        });

        binding.wvProductCreator.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                uploadMessage = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                filePickerLauncher.launch(intent);
                return true;
            }
        });

        binding.wvProductCreator.loadUrl("file:///android_asset/lox_dashboard.html");
    }

    public class WebAppInterface {
        /**
         * UPDATED: Bridge listener for HTML logs. 
         * Logic: Respects Console visibility and Professional mode filtering.
         */
        @JavascriptInterface
        public void logTechnicalEvent(String msg) {
            // ENHANCEMENT: Master Switch check
            if (!db.isConsoleLogEnabled()) return;

            String filteredMsg = msg;
            // ENHANCEMENT: Professional Summaries
            if (!db.isDebugModeActive()) {
                if (msg.contains("SCHEMA:")) filteredMsg = "Marketplace schema synchronized.";
                else if (msg.contains("R2_TRACE:")) filteredMsg = "Cloud storage link active.";
                else if (msg.contains("R2_SUCCESS:")) filteredMsg = "Asset successfully hosted in private cloud.";
                else if (msg.contains("NOSTR:")) filteredMsg = "Cryptographic proof generated for listing.";
                else if (msg.contains("WEBVIEW:")) filteredMsg = "Marketplace engine initialized.";
            }

            technicalConsole.append(filteredMsg).append("\n");
            Log.d(TAG, "Forensic: " + filteredMsg);
        }

        /**
         * =========================================================================
         * RETRIEVAL ENGINE: THE "RETRIEVE" ICON BRIDGE
         * Logic: When Advertiser clicks retrieve, launch the Full Screen Forensic Console.
         * It also forces a re-sync of the archive to repopulate the local bajaj models.
         * =========================================================================
         */
        @JavascriptInterface
        public void retrieveArchiveData() {
            runOnUiThread(() -> {
                logTechnicalEvent("ACTION: Native retrieval requested. Launching Forensic Console.");
                
                // Launch Forensic Console in Full Screen
                Intent intent = new Intent(CreateProductActivity.this, ReportActivity.class);
                startActivity(intent);

                // In parallel, force a heavy fetch from archive to update dropdowns in the background
                MarketplaceSchemaManager.fetchGlobalSchema(CreateProductActivity.this, schemaJson -> {
                    fetchedGlobalSchemaJson = schemaJson;
                    injectSchemaIfReady();
                    logTechnicalEvent("RETRIEVAL: Forensic archive successfully pulled and injected.");
                });
            });
        }

        // CROWDSOURCED SCHEMA: Native triggers from HTML Modals
        @JavascriptInterface
        public void publishNewCategory(String mainCat, String subCat) {
            MarketplaceSchemaManager.broadcastNewCategory(CreateProductActivity.this, mainCat, subCat);
        }

        @JavascriptInterface
        public void publishNewField(String category, String fieldName) {
            MarketplaceSchemaManager.broadcastNewField(CreateProductActivity.this, category, fieldName);
        }

        /**
         * UPDATED: Deletes a technical field permanently.
         * Logic: Informs Database to block the ID and triggers global Kind 5 broadcast.
         */
        @JavascriptInterface
        public void deleteField(String category, String fieldName) {
            logTechnicalEvent("ACTION: Permanent Deletion request for field '" + fieldName + "' in " + category);
            // Persistence: MarketplaceSchemaManager handles the local ID block and network broadcast
            MarketplaceSchemaManager.broadcastFieldDeletion(CreateProductActivity.this, category, fieldName);
        }

        /**
         * UPDATED: Deletes an entire sub-category permanently.
         * Logic: Blocks the hardcoded name locally and triggers global Kind 5 broadcast.
         */
        @JavascriptInterface
        public void deleteCategory(String categoryName) {
            logTechnicalEvent("ACTION: Permanent Category Deletion request for '" + categoryName + "'");

            // PERMANENCE FIX: Immediately mark the name as hidden in the local database
            db.addHiddenHardcodedName(categoryName);

            // GLOBAL FIX: Trigger the network broadcast to wipe for everyone else
            MarketplaceSchemaManager.broadcastCategoryDeletion(CreateProductActivity.this, categoryName);
        }

        /**
         * UPDATED: Native Bridge for Bulk Field Values Seeding
         * Supports context parameters for hierarchical data relationships.
         */
        @JavascriptInterface
        public void publishBulkSpecValues(String category, String fieldId, String commaSeparatedValues, String contextField, String contextValue) {
            MarketplaceSchemaManager.broadcastBulkValues(CreateProductActivity.this, category, fieldId, commaSeparatedValues, contextField, contextValue);
        }

        @JavascriptInterface
        public void processProductData(String jsonString, String mode) {
            runOnUiThread(() -> {
                if ("PREVIEW".equals(mode)) {
                    logTechnicalEvent("ACTION: Launching staging viewer...");
                    Intent intent = new Intent(CreateProductActivity.this, ProductPreviewActivity.class);
                    intent.putExtra("PRODUCT_JSON", jsonString);
                    startActivity(intent);
                } else {
                    logTechnicalEvent("ACTION: Initiating legacy publish flow...");
                    handleIncomingProduct(jsonString);
                }
            });
        }
    }

    /**
     * Logic: Starts the final broadcast phase.
     * UPDATED: Checks Console visibility before launching the RelayReportDialog.
     */
    private void handleIncomingProduct(String rawJson) {
        logTechnicalEvent("PUBLISH: Form data received. Size: " + rawJson.length());

        // ENHANCEMENT: Only show the popup if console is enabled
        if (db.isConsoleLogEnabled()) {
            RelayReportDialog report = RelayReportDialog.newInstance("MARKETPLACE PUBLISH", "Uploading metadata...", technicalConsole.toString());
            report.showSafe(getSupportFragmentManager(), "MARKET_LOG");

            try {
                JSONObject productData = new JSONObject(rawJson);
                String title = productData.getString("title");
                String price = productData.getString("price");
                // EXTRACT CATEGORY FOR THE STOREFRONT UI
                String category = productData.optString("category", "Uncategorized");

                // CROWDSOURCED SCHEMA: Push typed values to network auto-complete pool
                JSONObject specs = productData.optJSONObject("specs");
                if (specs != null && specs.length() > 0) {
                    MarketplaceSchemaManager.broadcastSpecValues(this, category, specs);
                    logTechnicalEvent("SCHEMA: Typed spec values pushed to global auto-complete pool.");
                }

                String fileName = "product_" + System.currentTimeMillis() + ".json";
                cloudHelper.uploadJsonFile(this, rawJson, fileName, new CloudflareHelper.CloudflareCallback() {
                    @Override
                    public void onStatusUpdate(String log) {
                        logTechnicalEvent("CF_TRACE: " + log);
                        runOnUiThread(() -> report.updateTechnicalLogs("Cloudflare Storage Link Active", technicalConsole.toString()));
                    }

                    @Override
                    public void onSuccess(String uploadedUrl, String fileId) {
                        broadcastMarketplacePointer(title, price, category, uploadedUrl);
                        runOnUiThread(() -> report.updateTechnicalLogs("Broadcasting to Nostr...", technicalConsole.toString()));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        runOnUiThread(() -> {
                            logTechnicalEvent("CF_FAIL: " + e.getMessage());
                            report.updateTechnicalLogs("CRITICAL ERROR", technicalConsole.toString());
                        });
                    }
                });
            } catch (Exception e) {
                logTechnicalEvent("PARSE_ERROR: " + e.getMessage());
            }
        } else {
            // Logic for Background Publish (No UI Console)
            executeSilentPublish(rawJson);
        }
    }

    /**
     * ENHANCEMENT: Handles the publish sequence without a UI popup if console is disabled.
     */
    private void executeSilentPublish(String rawJson) {
        try {
            JSONObject productData = new JSONObject(rawJson);
            String title = productData.getString("title");
            String price = productData.getString("price");
            String category = productData.optString("category", "Uncategorized");

            cloudHelper.uploadJsonFile(this, rawJson, "product_silent.json", new CloudflareHelper.CloudflareCallback() {
                @Override public void onStatusUpdate(String log) {}
                @Override public void onSuccess(String uploadedUrl, String fileId) {
                    broadcastMarketplacePointer(title, price, category, uploadedUrl);
                }
                @Override public void onFailure(Exception e) {
                    runOnUiThread(() -> Toast.makeText(CreateProductActivity.this, "Publish failed.", Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception ignored) {}
    }

    private void broadcastMarketplacePointer(String title, String price, String category, String cloudflareUrl) {
        try {
            JSONObject pointerContent = new JSONObject();
            pointerContent.put("title", title);
            pointerContent.put("price", price);
            pointerContent.put("category", category); // ADD CATEGORY TO POINTER
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

            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signedEvent != null) {
                logTechnicalEvent("NOSTR: Signed Kind 30005. ID: " + signedEvent.getString("id"));
                wsManager.broadcastEvent(signedEvent.toString());
                Toast.makeText(this, "Published Successfully!", Toast.LENGTH_LONG).show();
                finish();
            }
        } catch (Exception e) {
            logTechnicalEvent("SIGN_FAIL: " + e.getMessage());
        }
    }

    /**
     * COLLECTIVE MEMORY BRIDGE: Automatic Healing Logic
     * Triggered by MarketplaceSchemaManager when network amnesia is detected.
     * Re-signs and re-broadcasts the entire local schema anchor to refresh relay indexes.
     */
    public static void triggerAutomaticHealing(Context context) {
        new Thread(() -> {
            Log.w(TAG, "HEALER: Commencing background network restoration...");
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            WebSocketClientManager wsManager = WebSocketClientManager.getInstance();
            String anchorJson = db.getSchemaCache();

            if (anchorJson == null || anchorJson.equals("{}") || anchorJson.length() < 50) return;

            try {
                JSONObject schema = new JSONObject(anchorJson);

                // 1. Heal Categories (Kind 30006)
                JSONArray categories = schema.optJSONArray("categories");
                if (categories != null) {
                    for (int i = 0; i < categories.length(); i++) {
                        JSONObject cat = categories.getJSONObject(i);
                        cat.remove("_event_id"); // Strip internal database tag
                        executeSilentHealBroadcast(context, wsManager, 30006, cat);
                    }
                }

                // 2. Heal Fields (Kind 30006)
                JSONArray fields = schema.optJSONArray("fields");
                if (fields != null) {
                    for (int i = 0; i < fields.length(); i++) {
                        JSONObject field = fields.getJSONObject(i);
                        field.remove("_event_id");
                        executeSilentHealBroadcast(context, wsManager, 30006, field);
                    }
                }

                // 3. Heal Value Pools / Brands (Kind 30007)
                JSONArray values = schema.optJSONArray("values");
                if (values != null) {
                    for (int i = 0; i < values.length(); i++) {
                        JSONObject val = values.getJSONObject(i);
                        val.remove("_event_id");
                        executeSilentHealBroadcast(context, wsManager, 30007, val);
                    }
                }

                Log.i(TAG, "HEALER: Collective memory successfully pushed to relays.");

            } catch (Exception e) {
                Log.e(TAG, "HEALER_CRASH: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Internal logic for re-signing and bumping events for the auto-heal loop.
     */
    private static void executeSilentHealBroadcast(Context context, WebSocketClientManager ws, int kind, JSONObject content) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            JSONObject event = new JSONObject();
            event.put("kind", kind);
            event.put("pubkey", db.getPublicKey());
            // BUMP: Reset relay pruning clock with fresh timestamp
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", content.toString());

            JSONArray tags = new JSONArray();
            tags.put(new JSONArray().put("d").put("adnostr_schema_" + UUID.randomUUID().toString().substring(0, 8)));
            event.put("tags", tags);

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signed != null) {
                ws.broadcastEvent(signed.toString());
            }
        } catch (Exception ignored) {}
    }

    /**
     * UPDATED: Internal log helper. 
     * Logic: Respects Console visibility and Professional mode filtering.
     */
    private void logTechnicalEvent(String msg) {
        // ENHANCEMENT: Master Switch check
        if (!db.isConsoleLogEnabled()) return;

        String filteredMsg = msg;
        // ENHANCEMENT: Professional Summaries
        if (!db.isDebugModeActive()) {
            if (msg.contains("SCHEMA:")) filteredMsg = "Metadata definition synced.";
            else if (msg.contains("PICKER:")) filteredMsg = "Local media selected.";
            else if (msg.contains("R2:")) filteredMsg = "Initiating direct cloud tunnel.";
            else if (msg.contains("R2_TRACE:")) filteredMsg = "Storage link active.";
        }

        technicalConsole.append("[").append(System.currentTimeMillis()).append("] ").append(filteredMsg).append("\n");
        Log.d(TAG, filteredMsg);
    }

    @Override
    protected void onDestroy() {
        // UI AUTO-REFRESH: Remove the listener to prevent memory leaks
        if (wsManager != null) {
            wsManager.removeSchemaListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (binding.wvProductCreator.canGoBack()) {
            binding.wvProductCreator.goBack();
        } else {
            super.onBackPressed();
        }
    }
}