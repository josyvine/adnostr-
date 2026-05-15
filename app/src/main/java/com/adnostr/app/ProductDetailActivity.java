package com.adnostr.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.adnostr.app.databinding.ActivityProductDetailBinding;

import java.nio.charset.StandardCharsets;

/**
 * FEATURE 5: Product Detail Activity.
 * Logic: Receives a Cloudflare URL -> Downloads full Product JSON -> 
 * Loads lox_viewer.html asset -> Injects JSON via JavaScript render function.
 * UPDATED: Uses Base64 encoding for the JS bridge to prevent crashes from dynamic text input.
 * 
 * THEME ENGINE UPDATE:
 * - Dynamic UI: Adapts status bar and WebView skin to Day/Night mode.
 * 
 * TOTAL SURVEILLANCE UPDATE:
 * - reportGlitchedLogic: New bridge method to capture UI inconsistencies in the viewer.
 * - Performance Tracking: Measures download-to-render latency for forensic reports.
 * - CTA Tracking: Logs every external click (Call, WhatsApp) to identify UX blockages.
 */
public class ProductDetailActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_ProdDetail";
    private ActivityProductDetailBinding binding;
    private CloudflareHelper cloudHelper;
    private AdNostrDatabaseHelper db;
    private String productJsonUrl;
    private String downloadedJson = "";
    private boolean isPageLoaded = false;

    // Forensic performance tracking
    private long startSessionTime = 0;
    private long downloadFinishTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TOTAL SURVEILLANCE: Mark entry into Viewer
        ActionReportLogger.logAction("VIEWER_OPEN", "ProductDetailActivity initiated.");
        startSessionTime = System.currentTimeMillis();

        db = AdNostrDatabaseHelper.getInstance(this);

        // THEME ENGINE: Conditional Status Bar Logic
        if (db.isDayMode()) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.white));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }

        binding = ActivityProductDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cloudHelper = new CloudflareHelper();

        // 1. Get the JSON Pointer URL from the Intent
        productJsonUrl = getIntent().getStringExtra("PRODUCT_JSON_URL");

        if (productJsonUrl == null || productJsonUrl.isEmpty()) {
            ActionReportLogger.logLogicViolation("INVALID_INTENT", "ProductDetailActivity launched with null URL.");
            Toast.makeText(this, "Product link is invalid.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();

        // 2. Start downloading the heavy JSON in parallel with WebView loading
        fetchProductDetails();

        binding.btnBackDetail.setOnClickListener(v -> {
            ActionReportLogger.logAction("VIEWER_EXIT", "User clicked back button.");
            finish();
        });
    }

    /**
     * Configures the WebView to host the premium e-commerce template.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = binding.wvProductDetail.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Attach the bridge for total surveillance
        binding.wvProductDetail.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        binding.wvProductDetail.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // TOTAL SURVEILLANCE: Track external CTA triggers
                ActionReportLogger.logAction("VIEWER_CTA", "User triggered external intent: " + url);

                if (url.startsWith("tel:") || url.startsWith("whatsapp:") || url.contains("wa.me")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        if (url.startsWith("tel:")) {
                            intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
                        }
                        startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        ActionReportLogger.logUxBlockage("CTA_FAILURE", url + " -> " + e.getMessage());
                        Toast.makeText(ProductDetailActivity.this, "Application not found to handle this action.", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                Log.d(TAG, "WebView Template Loaded.");
                ActionReportLogger.logPerformance("VIEWER_DOM_READY", "Viewer HTML fully parsed.");

                // THEME ENGINE: Synchronize WebView theme with native preference
                String theme = db.isDayMode() ? "day" : "night";
                binding.wvProductDetail.evaluateJavascript("if(window.setTheme) setTheme('" + theme + "');", null);
                
                // If data was downloaded before the page finished loading, inject it now
                if (!downloadedJson.isEmpty()) {
                    injectJsonToViewer();
                }
            }
        });

        // Load the template from assets
        binding.wvProductDetail.loadUrl("file:///android_asset/lox_viewer.html");
    }

    /**
     * Downloads the full product JSON file from Cloudflare R2.
     */
    private void fetchProductDetails() {
        final long downloadStartTime = System.currentTimeMillis();
        ActionReportLogger.logAction("VIEWER_DOWNLOAD", "Initiating JSON fetch from R2: " + productJsonUrl);

        cloudHelper.downloadJsonFile(productJsonUrl, new CloudflareHelper.JsonDownloadCallback() {
            @Override
            public void onDownloadSuccess(String jsonContent) {
                downloadFinishTime = System.currentTimeMillis();
                downloadedJson = jsonContent;
                Log.d(TAG, "JSON Download Success.");
                ActionReportLogger.logPerformance("VIEWER_DOWNLOAD_LATENCY", "R2 JSON fetch took " + (downloadFinishTime - downloadStartTime) + "ms");
                
                // If the WebView is ready, inject the data
                if (isPageLoaded) {
                    injectJsonToViewer();
                }
            }

            @Override
            public void onDownloadFailure(Exception e) {
                runOnUiThread(() -> {
                    ActionReportLogger.logError("VIEWER_DOWNLOAD_FAIL", "Source: " + productJsonUrl + " Error: " + e.getMessage());
                    Toast.makeText(ProductDetailActivity.this, "Failed to retrieve product data.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Download error: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Bridge Logic: Passes the downloaded JSON string into the HTML template's 
     * renderProduct() JavaScript function.
     * FIXED: Uses Base64 to ensure complex dynamic specs (quotes, emojis, newlines) don't break JS.
     */
    private void injectJsonToViewer() {
        final long injectStartTime = System.currentTimeMillis();
        runOnUiThread(() -> {
            try {
                // Encode the JSON to Base64 to safely pass it through the evaluateJavascript bridge
                String base64Encoded = Base64.encodeToString(downloadedJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                
                // Construct the JS execution string. 
                // decodeURIComponent(escape(window.atob(...))) safely converts Base64 back to UTF-8 JS string
                String jsCommand = "renderProduct(decodeURIComponent(escape(window.atob('" + base64Encoded + "'))))";
                
                binding.wvProductDetail.evaluateJavascript(jsCommand, null);
                Log.i(TAG, "Product JSON safely injected into template via Base64 bridge.");
                
                long injectDuration = System.currentTimeMillis() - injectStartTime;
                long totalLatency = System.currentTimeMillis() - startSessionTime;
                
                ActionReportLogger.logPerformance("VIEWER_INJECTION_LATENCY", "JS renderProduct call took " + injectDuration + "ms");
                ActionReportLogger.logPerformance("VIEWER_TOTAL_TTF", "Total time to first render: " + totalLatency + "ms");
                
            } catch (Exception e) {
                ActionReportLogger.logError("VIEWER_INJECTION_CRASH", e.getMessage());
                Log.e(TAG, "Failed to inject JSON via Base64: " + e.getMessage());
            }
        });
    }

    /**
     * WebAppInterface for Viewer Forensic Reporting.
     */
    public class WebAppInterface {
        @JavascriptInterface
        public void logTechnicalEvent(String msg) {
            ActionReportLogger.logAction("VIEWER_JS_EVENT", msg);
        }

        @JavascriptInterface
        public void reportGlitchedLogic(String type, String details) {
            ActionReportLogger.logHtmlGlitch(type, details);
            Log.e(TAG, "FORENSIC_VIEWER_ALERT: [" + type + "] " + details);
        }
    }

    @Override
    public void onBackPressed() {
        if (binding.wvProductDetail.canGoBack()) {
            binding.wvProductDetail.goBack();
        } else {
            super.onBackPressed();
        }
    }
}