package com.adnostr.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ActivityAdPopupBinding;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import coil.Coil;
import coil.request.ImageRequest;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Professional Ad Delivery Overlay.
 * UPDATED: Replaced IPFS P2P resolution with Encrypted Media Relay (HTTPS) logic.
 * Logic: Download Encrypted Bytes -> Decrypt with AES Key from Nostr JSON -> Render Image.
 */
public class AdPopupActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdPopup";
    private ActivityAdPopupBinding binding;
    private final OkHttpClient httpClient = new OkHttpClient();
    private String adDecryptionKeyHex = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Full-Screen Overlay Flags
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityAdPopupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String adJsonString = getIntent().getStringExtra("AD_PAYLOAD_JSON");

        if (adJsonString == null || adJsonString.isEmpty()) {
            finish();
            return;
        }

        try {
            parseAndPopulateAd(adJsonString);
        } catch (Exception e) {
            // Extract raw Java exception for the diagnostic screen
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String rawStackTrace = sw.toString();

            Intent errorIntent = new Intent(this, ErrorDisplayActivity.class);
            errorIntent.putExtra("ERROR_DETAILS", "Ad Rendering Failure:\n\nPayload:\n" + adJsonString + "\n\nRaw Java Error:\n" + rawStackTrace);
            errorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(errorIntent);
            finish();
        }

        binding.btnCloseAd.setOnClickListener(v -> finish());
        binding.viewBackgroundOverlay.setOnClickListener(v -> finish());
    }

    private void parseAndPopulateAd(String jsonStr) throws Exception {
        JSONObject event;
        if (jsonStr.trim().startsWith("[")) {
            JSONArray relayMsg = new JSONArray(jsonStr);
            event = relayMsg.getJSONObject(2);
        } else {
            event = new JSONObject(jsonStr);
        }

        String contentRaw = event.optString("content", "");
        if (contentRaw.isEmpty()) throw new Exception("Ad content field is empty.");

        JSONObject content = new JSONObject(contentRaw);

        // Populate Text
        String title = content.optString("title", "");
        if (title.isEmpty()) throw new Exception("Required field 'title' is missing.");
        binding.tvPopupTitle.setText(title);
        binding.tvPopupDesc.setText(content.optString("desc", "No description provided."));

        // ENHANCEMENT: Extract the AES Decryption Key
        adDecryptionKeyHex = content.optString("key", "");

        // Handle Image Slider (Extract HTTPS URLs from JSON)
        List<String> imageUrls = new ArrayList<>();
        Object imageObj = content.opt("image");
        
        if (imageObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) imageObj;
            for (int i = 0; i < arr.length(); i++) {
                imageUrls.add(arr.getString(i));
            }
        } else if (imageObj instanceof String && !((String) imageObj).isEmpty()) {
            imageUrls.add((String) imageObj);
        }

        if (!imageUrls.isEmpty()) {
            setupImageSlider(imageUrls);
        } else {
            binding.vpAdImages.setVisibility(View.GONE);
        }

        setupActionButtons(content);
    }

    private void setupImageSlider(List<String> urls) {
        ImageSliderAdapter adapter = new ImageSliderAdapter(urls);
        binding.vpAdImages.setAdapter(adapter);

        // Connect the dots (Page Indicator)
        new TabLayoutMediator(binding.tabDots, binding.vpAdImages, (tab, position) -> {}).attach();
    }

    private void setupActionButtons(JSONObject content) {
        // WhatsApp (cta)
        String cta = content.optString("cta", "");
        if (!cta.isEmpty()) {
            binding.btnActionWhatsapp.setVisibility(View.VISIBLE);
            binding.btnActionWhatsapp.setOnClickListener(v -> openUrl(cta));
        }

        // Google Maps (maps)
        String maps = content.optString("maps", "");
        if (!maps.isEmpty()) {
            binding.btnActionMap.setVisibility(View.VISIBLE);
            binding.btnActionMap.setOnClickListener(v -> openUrl(maps));
        }

        // External Link (website)
        String web = content.optString("website", "");
        if (!web.isEmpty()) {
            binding.btnActionWebsite.setVisibility(View.VISIBLE);
            binding.btnActionWebsite.setOnClickListener(v -> openUrl(web));
        }

        // Phone (call)
        String call = content.optString("call", "");
        if (!call.isEmpty()) {
            binding.btnActionCall.setVisibility(View.VISIBLE);
            binding.btnActionCall.setOnClickListener(v -> {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + call)));
            });
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    /**
     * Internal Adapter for the Image ViewPager slider.
     * UPDATED: Downloads encrypted bytes from HTTP Media Relay and decrypts locally.
     */
    private class ImageSliderAdapter extends RecyclerView.Adapter<ImageSliderAdapter.ViewHolder> {
        private final List<String> urls;

        ImageSliderAdapter(List<String> urls) { this.urls = urls; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new ViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String url = urls.get(position);
            ImageView targetView = (ImageView) holder.itemView;

            // Start Secure Image Resolution Flow
            fetchAndDecryptMedia(url, targetView);
        }

        /**
         * Secure Fetch Flow: Download -> Decrypt -> Display.
         */
        private void fetchAndDecryptMedia(String url, ImageView imageView) {
            Request request = new Request.Builder().url(url).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "Failed to download encrypted media: " + e.getMessage());
                    runOnUiThread(() -> imageView.setImageResource(android.R.drawable.ic_menu_report_image));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> imageView.setImageResource(android.R.drawable.ic_menu_report_image));
                        return;
                    }

                    try {
                        byte[] encryptedBytes = response.body().bytes();

                        // Decrypt only if a key is provided in the Ad
                        byte[] finalImageData;
                        if (!adDecryptionKeyHex.isEmpty()) {
                            byte[] aesKey = EncryptionUtils.hexToBytes(adDecryptionKeyHex);
                            finalImageData = EncryptionUtils.decrypt(encryptedBytes, aesKey);
                        } else {
                            // Fallback to raw if no key (unencrypted ad support)
                            finalImageData = encryptedBytes;
                        }

                        // Display via Coil on UI Thread
                        runOnUiThread(() -> {
                            ImageRequest imageRequest = new ImageRequest.Builder(AdPopupActivity.this)
                                    .data(finalImageData)
                                    .crossfade(true)
                                    .target(imageView)
                                    .build();
                            Coil.imageLoader(AdPopupActivity.this).enqueue(imageRequest);
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Decryption Error for " + url + ": " + e.getMessage());
                        runOnUiThread(() -> imageView.setImageResource(android.R.drawable.ic_menu_report_image));
                    }
                }
            });
        }

        @Override
        public int getItemCount() { return urls.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(@NonNull View itemView) { super(itemView); }
        }
    }
}