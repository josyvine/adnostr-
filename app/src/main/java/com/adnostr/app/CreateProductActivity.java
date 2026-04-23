package com.adnostr.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.UUID;

/**
 * FEATURE 5: Product Creator Activity.
 * Hosts the lox_dashboard.html in a WebView and bridges data to Nostr.
 * Logic: Dashboard (HTML) -> JS Bridge (Java) -> Cloudflare (JSON File) -> Nostr (Kind 30005 Pointer).
 * FIXED (Glitch 5): Implemented WebChromeClient and ActivityResultLauncher to handle HTML file uploads.
 */
public class CreateProductActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_CreateProduct";
    private ActivityCreateProductBinding binding;
    private AdNostrDatabaseHelper db;
    private CloudflareHelper cloudHelper;
    private WebSocketClientManager wsManager;

    // Callbacks for the WebView File Chooser
    private ValueCallback<Uri[]> uploadMessage;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCreateProductBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        cloudHelper = new CloudflareHelper();
        wsManager = WebSocketClientManager.getInstance();

        // Register the file picker result handler
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (uploadMessage == null) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri[] results = null;
                        // Handle multiple selection if the intent supports it
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = result.getData().getClipData().getItemAt(i).getUri();
                            }
                        } else if (result.getData().getData() != null) {
                            // Handle single selection
                            results = new Uri[]{result.getData().getData()};
                        }
                        uploadMessage.onReceiveValue(results);
                    } else {
                        // User cancelled the picker
                        uploadMessage.onReceiveValue(null);
                    }
                    uploadMessage = null; // Reset callback
                }
        );

        setupWebView();

        binding.btnBackCreator.setOnClickListener(v -> finish());
    }

    /**
     * Configures the WebView to support the modern Dashboard features.
     * FIXED (Glitch 5): Added WebChromeClient to bridge HTML file inputs to Android Intents.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = binding.wvProductCreator.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Register the JS Bridge so the HTML can talk to Android
        binding.wvProductCreator.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        binding.wvProductCreator.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Marketplace Dashboard Loaded successfully.");
            }
        });

        // Set the WebChromeClient to handle <input type="file">
        binding.wvProductCreator.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                // Ensure any existing callback is cleared
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }

                uploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                // Enable multi-select if the HTML input specifies 'multiple'
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    filePickerLauncher.launch(intent);
                } catch (Exception e) {
                    uploadMessage = null;
                    Toast.makeText(CreateProductActivity.this, "Cannot open file chooser", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Load the local HTML asset
        binding.wvProductCreator.loadUrl("file:///android_asset/lox_dashboard.html");
    }

    /**
     * JavaScript Interface Bridge.
     * This class receives the product JSON from the HTML form.
     */
    public class WebAppInterface {
        @JavascriptInterface
        public void processProductData(String jsonString) {
            runOnUiThread(() -> handleIncomingProduct(jsonString));
        }
    }

    /**
     * CORE LOGIC: Orchestrates the Marketplace Publishing Flow.
     */
    private void handleIncomingProduct(String rawJson) {
        try {
            JSONObject productData = new JSONObject(rawJson);
            String title = productData.getString("title");
            String price = productData.getString("price");

            Toast.makeText(this, "Uploading Listing to Cloudflare...", Toast.LENGTH_SHORT).show();

            // STEP 1: Upload the full heavy JSON to Private Cloudflare R2
            String fileName = "product_" + System.currentTimeMillis() + ".json";
            cloudHelper.uploadJsonFile(this, rawJson, fileName, new CloudflareHelper.CloudflareCallback() {
                @Override
                public void onStatusUpdate(String log) { Log.d(TAG, log); }

                @Override
                public void onSuccess(String uploadedUrl, String fileId) {
                    // STEP 2: Broadcast the lightweight Kind 30005 Pointer to Nostr Relays
                    broadcastMarketplacePointer(title, price, uploadedUrl);
                }

                @Override
                public void onFailure(Exception e) {
                    Toast.makeText(CreateProductActivity.this, "Cloudflare Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Product processing failed: " + e.getMessage());
            Toast.makeText(this, "JSON Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Broadcasts the Kind 30005 pointer event to the network.
     */
    private void broadcastMarketplacePointer(String title, String price, String cloudflareUrl) {
        try {
            // 1. Construct the content payload (Simple metadata + Link)
            JSONObject pointerContent = new JSONObject();
            pointerContent.put("title", title);
            pointerContent.put("price", price);
            pointerContent.put("json_url", cloudflareUrl);

            // 2. Prepare Kind 30005 (Marketplace Pointer)
            JSONObject event = new JSONObject();
            event.put("kind", 30005);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", pointerContent.toString());

            JSONArray tags = new JSONArray();
            // d-tag for replaceable marketplace events
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_listing_" + UUID.randomUUID().toString().substring(0, 8));
            tags.put(dTag);
            
            // t-tags for directory discovery
            JSONArray tTag = new JSONArray();
            tTag.put("t");
            tTag.put("marketplace_product");
            tags.put(tTag);
            
            event.put("tags", tags);

            // 3. Sign and Broadcast to all active relays
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signedEvent != null) {
                wsManager.broadcastEvent(signedEvent.toString());
                Log.i(TAG, "Marketplace Pointer Live: " + cloudflareUrl);
                
                Toast.makeText(this, "Product Published Successfully!", Toast.LENGTH_LONG).show();
                finish();
            } else {
                throw new Exception("Signing failed");
            }

        } catch (Exception e) {
            Log.e(TAG, "Nostr Broadcast Failed: " + e.getMessage());
            Toast.makeText(this, "Network error. Please try again.", Toast.LENGTH_SHORT).show();
        }
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