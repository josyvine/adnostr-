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
 * 
 * DISTRIBUTED MEMORY FIX (DAY ZERO):
 * - Retrieve Bridge: Now shows a Detailed Gathering Console Overlay for ALL advertisers (Admin and B).
 * - Archiving: Every advertiser device now sniffs and saves database frames to their local archive.
 * 
 * 4-TIER RETRIEVAL UPDATE:
 * - Job 1 (retrieveCategorySchema): Discovery scan for T1, T2, and T3 anchors.
 * - Job 2 (ejectTechSpecValues): Targeted ejection of T4 values for a specific sub-category.
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
        MarketplaceSchemaManager.fetchGlobalSchema(this, null, schemaJson -> {
            fetchedGlobalSchemaJson = schemaJson;
            logTechnicalEvent("SCHEMA: Global JSON downloaded from forensic archive. Length: " + schemaJson.length());
            injectSchemaIfReady();
        }, null);

        setupWebView();
        binding.btnBackCreator.setOnClickListener(v -> finish());
    }

    /**
     * UI AUTO-REFRESH: Implementation of SchemaEventListener.
     * REPAIR UPDATE: Triggers immediate fetch of merged forensic archive data.
     */
    @Override
    public void onSchemaEventReceived(String url, JSONObject event) {
        runOnUiThread(() -> {
            // Re-fetch with the newly arrived metadata merged from the Hard-Locked Archive
            MarketplaceSchemaManager.fetchGlobalSchema(this, null, schemaJson -> {
                fetchedGlobalSchemaJson = schemaJson;
                // Zero-Refresh Injection: Repopulate dropdowns in real-time
                injectSchemaIfReady();
                logTechnicalEvent("UI_SYNC: Dropdowns refreshed with live data from " + url);
            }, null);
        });
    }

    /**
     * Injects the heavy schema into the HTML safely using Base64.
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

        @JavascriptInterface
        public void logTechnicalEvent(String msg) {
            if (!db.isConsoleLogEnabled()) return;

            String filteredMsg = msg;
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
         * JOB 1: THE DISCOVERY BRIDGE
         * Triggered by the "Retrieve" icon on the Category area.
         * Scans for T1 (Main), T2 (Sub), and T3 (Fields).
         * =========================================================================
         */
        @JavascriptInterface
        public void retrieveCategorySchema() {
            runOnUiThread(() -> {
                final StringBuilder gatheringLogs = new StringBuilder();
                gatheringLogs.append("=== INITIATING CATEGORY DISCOVERY SEQUENCE ===\n");
                gatheringLogs.append("SCOPE: Tiers 1, 2, and 3 (Structural Anchors)\n\n");

                RelayReportDialog reportDialog = RelayReportDialog.newInstance(
                        "DISCOVERY CONSOLE", 
                        "Gathering structural anchors...", 
                        gatheringLogs.toString()
                );
                reportDialog.showSafe(getSupportFragmentManager(), "GATHER_LOG");

                MarketplaceSchemaManager.fetchGlobalSchema(CreateProductActivity.this, null, schemaJson -> {
                    fetchedGlobalSchemaJson = schemaJson;
                    injectSchemaIfReady();
                    gatheringLogs.append("\n[SUCCESS] Discovery complete. Sub-categories detected.");
                    reportDialog.updateTechnicalLogs("DISCOVERY COMPLETE", gatheringLogs.toString());
                }, message -> {
                    gatheringLogs.append(message).append("\n");
                    reportDialog.updateTechnicalLogs("DISCOVERY: Gathering structural frames...", gatheringLogs.toString());
                });
            });
        }

        /**
         * =========================================================================
         * JOB 2: THE EJECTION BRIDGE
         * Triggered by the "Retrieve" icon on the Technical Specs area.
         * Target: Ejects T4 Values (Bajaj/Models) for a specific Sub-Category.
         * =========================================================================
         */
        @JavascriptInterface
        public void ejectTechSpecValues(String selectedSub) {
            runOnUiThread(() -> {
                final StringBuilder ejectionLogs = new StringBuilder();
                ejectionLogs.append("=== INITIATING VALUE EJECTION SEQUENCE ===\n");
                ejectionLogs.append("TARGET: Tier 4 Data Pools for [" + selectedSub + "]\n\n");

                RelayReportDialog reportDialog = RelayReportDialog.newInstance(
                        "EJECTION CONSOLE", 
                        "Pulling data pools from archive...", 
                        ejectionLogs.toString()
                );
                reportDialog.showSafe(getSupportFragmentManager(), "EJECT_LOG");

                MarketplaceSchemaManager.fetchGlobalSchema(CreateProductActivity.this, selectedSub, schemaJson -> {
                    fetchedGlobalSchemaJson = schemaJson;
                    injectSchemaIfReady();
                    ejectionLogs.append("\n[SUCCESS] Ejection complete. Spec values injected.");
                    reportDialog.updateTechnicalLogs("EJECTION SUCCESSFUL", ejectionLogs.toString());
                }, message -> {
                    ejectionLogs.append(message).append("\n");
                    reportDialog.updateTechnicalLogs("EJECTION: Extracting specific values...", ejectionLogs.toString());
                });
            });
        }

        @JavascriptInterface
        public void retrieveArchiveData() {
            // Legacy wrapper calling Job 1 by default
            retrieveCategorySchema();
        }

        @JavascriptInterface
        public void publishNewCategory(String mainCat, String subCat) {
            MarketplaceSchemaManager.broadcastNewCategory(CreateProductActivity.this, mainCat, subCat);
        }

        @JavascriptInterface
        public void publishNewField(String category, String fieldName) {
            MarketplaceSchemaManager.broadcastNewField(CreateProductActivity.this, category, fieldName);
        }

        @JavascriptInterface
        public void deleteField(String category, String fieldName) {
            logTechnicalEvent("ACTION: Permanent Deletion request for field '" + fieldName + "' in " + category);
            MarketplaceSchemaManager.broadcastFieldDeletion(CreateProductActivity.this, category, fieldName);
        }

        @JavascriptInterface
        public void deleteCategory(String categoryName) {
            logTechnicalEvent("ACTION: Permanent Category Deletion request for '" + categoryName + "'");
            db.addHiddenHardcodedName(categoryName);
            MarketplaceSchemaManager.broadcastCategoryDeletion(CreateProductActivity.this, categoryName);
        }

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

    private void handleIncomingProduct(String rawJson) {
        logTechnicalEvent("PUBLISH: Form data received. Size: " + rawJson.length());

        if (db.isConsoleLogEnabled()) {
            RelayReportDialog report = RelayReportDialog.newInstance("MARKETPLACE PUBLISH", "Uploading metadata...", technicalConsole.toString());
            report.showSafe(getSupportFragmentManager(), "MARKET_LOG");

            try {
                JSONObject productData = new JSONObject(rawJson);
                String title = productData.getString("title");
                String price = productData.getString("price");
                String category = productData.optString("category", "Uncategorized");

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
            executeSilentPublish(rawJson);
        }
    }

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
            pointerContent.put("category", category); 
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

    public static void triggerAutomaticHealing(Context context) {
        new Thread(() -> {
            Log.w(TAG, "HEALER: Commencing background network restoration...");
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            WebSocketClientManager wsManager = WebSocketClientManager.getInstance();
            String anchorJson = db.getSchemaCache();

            if (anchorJson == null || anchorJson.equals("{}") || anchorJson.length() < 50) return;

            try {
                JSONObject schema = new JSONObject(anchorJson);

                JSONArray categories = schema.optJSONArray("categories");
                if (categories != null) {
                    for (int i = 0; i < categories.length(); i++) {
                        JSONObject cat = categories.getJSONObject(i);
                        cat.remove("_event_id"); 
                        executeSilentHealBroadcast(context, wsManager, 30006, cat);
                    }
                }

                JSONArray fields = schema.optJSONArray("fields");
                if (fields != null) {
                    for (int i = 0; i < fields.length(); i++) {
                        JSONObject field = fields.getJSONObject(i);
                        field.remove("_event_id");
                        executeSilentHealBroadcast(context, wsManager, 30006, field);
                    }
                }

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

    private static void executeSilentHealBroadcast(Context context, WebSocketClientManager ws, int kind, JSONObject content) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            JSONObject event = new JSONObject();
            event.put("kind", kind);
            event.put("pubkey", db.getPublicKey());
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

    private void logTechnicalEvent(String msg) {
        if (!db.isConsoleLogEnabled()) return;

        String filteredMsg = msg;
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