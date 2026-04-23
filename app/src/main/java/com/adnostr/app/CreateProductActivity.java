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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * FEATURE 5: Product Creator Activity.
 * FIXED: Image pipeline now uploads to R2 and pushes URLs back to HTML.
 * FIXED: Detailed Forensic Logging integrated via logTechnicalEvent bridge.
 * FIXED: Implements Preview logic to staging viewer before broadcast.
 */
public class CreateProductActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_CreateProduct";
    private ActivityCreateProductBinding binding;
    private AdNostrDatabaseHelper db;
    private CloudflareHelper cloudHelper;
    private WebSocketClientManager wsManager;

    private ValueCallback<Uri[]> uploadMessage;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    
    // Forensic log accumulator
    private final StringBuilder technicalConsole = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCreateProductBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        cloudHelper = new CloudflareHelper();
        wsManager = WebSocketClientManager.getInstance();

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

        setupWebView();
        binding.btnBackCreator.setOnClickListener(v -> finish());
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
                logTechnicalEvent("WEBVIEW: Dashboard DOM Loaded.");
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
            technicalConsole.append(msg).append("\n");
            Log.d(TAG, "Forensic: " + msg);
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
        
        RelayReportDialog report = RelayReportDialog.newInstance("MARKETPLACE PUBLISH", "Uploading metadata...", technicalConsole.toString());
        report.showSafe(getSupportFragmentManager(), "MARKET_LOG");

        try {
            JSONObject productData = new JSONObject(rawJson);
            String title = productData.getString("title");
            String price = productData.getString("price");

            String fileName = "product_" + System.currentTimeMillis() + ".json";
            cloudHelper.uploadJsonFile(this, rawJson, fileName, new CloudflareHelper.CloudflareCallback() {
                @Override
                public void onStatusUpdate(String log) {
                    logTechnicalEvent("CF_TRACE: " + log);
                    runOnUiThread(() -> report.updateTechnicalLogs("Cloudflare Storage Link Active", technicalConsole.toString()));
                }

                @Override
                public void onSuccess(String uploadedUrl, String fileId) {
                    broadcastMarketplacePointer(title, price, uploadedUrl);
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
    }

    private void broadcastMarketplacePointer(String title, String price, String cloudflareUrl) {
        try {
            JSONObject pointerContent = new JSONObject();
            pointerContent.put("title", title);
            pointerContent.put("price", price);
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

    private void logTechnicalEvent(String msg) {
        technicalConsole.append("[").append(System.currentTimeMillis()).append("] ").append(msg).append("\n");
        Log.d(TAG, msg);
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