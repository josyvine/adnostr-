package com.adnostr.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityProductDetailBinding;

import java.nio.charset.StandardCharsets;

/**
 * FEATURE 5: Product Detail Activity.
 * Logic: Receives a Cloudflare URL -> Downloads full Product JSON -> 
 * Loads lox_viewer.html asset -> Injects JSON via JavaScript render function.
 * UPDATED: Uses Base64 encoding for the JS bridge to prevent crashes from dynamic text input.
 */
public class ProductDetailActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_ProdDetail";
    private ActivityProductDetailBinding binding;
    private CloudflareHelper cloudHelper;
    private String productJsonUrl;
    private String downloadedJson = "";
    private boolean isPageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityProductDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cloudHelper = new CloudflareHelper();

        // 1. Get the JSON Pointer URL from the Intent
        productJsonUrl = getIntent().getStringExtra("PRODUCT_JSON_URL");

        if (productJsonUrl == null || productJsonUrl.isEmpty()) {
            Toast.makeText(this, "Product link is invalid.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupWebView();

        // 2. Start downloading the heavy JSON in parallel with WebView loading
        fetchProductDetails();

        binding.btnBackDetail.setOnClickListener(v -> finish());
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

        binding.wvProductDetail.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                Log.d(TAG, "WebView Template Loaded.");
                
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
        cloudHelper.downloadJsonFile(productJsonUrl, new CloudflareHelper.JsonDownloadCallback() {
            @Override
            public void onDownloadSuccess(String jsonContent) {
                downloadedJson = jsonContent;
                Log.d(TAG, "JSON Download Success.");
                
                // If the WebView is ready, inject the data
                if (isPageLoaded) {
                    injectJsonToViewer();
                }
            }

            @Override
            public void onDownloadFailure(Exception e) {
                runOnUiThread(() -> {
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
        runOnUiThread(() -> {
            try {
                // Encode the JSON to Base64 to safely pass it through the evaluateJavascript bridge
                String base64Encoded = Base64.encodeToString(downloadedJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                
                // Construct the JS execution string. 
                // decodeURIComponent(escape(window.atob(...))) safely converts Base64 back to UTF-8 JS string
                String jsCommand = "renderProduct(decodeURIComponent(escape(window.atob('" + base64Encoded + "'))))";
                
                binding.wvProductDetail.evaluateJavascript(jsCommand, null);
                Log.i(TAG, "Product JSON safely injected into template via Base64 bridge.");
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to inject JSON via Base64: " + e.getMessage());
            }
        });
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