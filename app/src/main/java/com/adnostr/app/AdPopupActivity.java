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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

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
 * UPDATED: Integrated Brand Logo and "Verified" status display.
 * UPDATED: Implements Synchronized Text Slider matching the Image Slider.
 * UPDATED: Mapped Modern Icon Actions for WhatsApp, Instagram, and Phone.
 * UPDATED: Supports IS_PREVIEW flag for advertiser testing.
 * Logic: Download Encrypted Bytes -> Decrypt -> Render Image + Sync Text.
 */
public class AdPopupActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdPopup";
    private ActivityAdPopupBinding binding;
    private final OkHttpClient httpClient = new OkHttpClient();
    private String adDecryptionKeyHex = "";
    private final List<String> imageUrls = new ArrayList<>();
    private final List<String> textChunks = new ArrayList<>();
    private boolean isPreview = false;

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

        // Check for Preview Mode
        isPreview = getIntent().getBooleanExtra("IS_PREVIEW", false);
        String adJsonString = getIntent().getStringExtra("AD_PAYLOAD_JSON");

        if (adJsonString == null || adJsonString.isEmpty()) {
            finish();
            return;
        }

        try {
            parseAndPopulateAd(adJsonString);
        } catch (Exception e) {
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

        // NEW: Zoom Button Listener
        binding.btnZoomImage.setOnClickListener(v -> {
            if (!imageUrls.isEmpty()) {
                int currentPos = binding.vpAdImages.getCurrentItem();
                String targetUrl = imageUrls.get(currentPos);
                
                Intent zoomIntent = new Intent(this, ImageZoomActivity.class);
                zoomIntent.putExtra("ZOOM_URL", targetUrl);
                zoomIntent.putExtra("AES_KEY", adDecryptionKeyHex);
                startActivity(zoomIntent);
            }
        });

        // NEW: Sync Text Slider with Image Slider
        binding.vpAdImages.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (position < textChunks.size()) {
                    binding.vpAdText.setCurrentItem(position, true);
                }
            }
        });
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

        // 1. Extract and Load Advertiser Logo
        String logoUrl = content.optString("logo", "");
        if (!logoUrl.isEmpty()) {
            ImageRequest logoReq = new ImageRequest.Builder(this)
                    .data(logoUrl)
                    .crossfade(true)
                    .target(binding.ivAdLogo)
                    .build();
            Coil.imageLoader(this).enqueue(logoReq);
        }

        // 2. Handle Image Slider (HTTPS URLs)
        imageUrls.clear();
        Object imageObj = content.opt("image");
        if (imageObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) imageObj;
            for (int i = 0; i < arr.length(); i++) {
                imageUrls.add(arr.getString(i));
            }
        } else if (imageObj instanceof String && !((String) imageObj).isEmpty()) {
            imageUrls.add((String) imageObj);
        }

        // 3. Handle Intelligent Description Slider (Chunked Text)
        textChunks.clear();
        String title = content.optString("title", "Ad");
        Object descObj = content.opt("desc");
        if (descObj instanceof JSONArray) {
            JSONArray descArr = (JSONArray) descObj;
            for (int i = 0; i < descArr.length(); i++) {
                textChunks.add(descArr.getString(i));
            }
        } else {
            textChunks.add(content.optString("desc", ""));
        }

        // Extracts the AES Key
        adDecryptionKeyHex = content.optString("key", "");

        if (!imageUrls.isEmpty()) {
            setupImageSlider(imageUrls);
            setupTextSlider(title, textChunks);
        }

        setupActionButtons(content);
    }

    private void setupImageSlider(List<String> urls) {
        ImageSliderAdapter adapter = new ImageSliderAdapter(urls);
        binding.vpAdImages.setAdapter(adapter);
        new TabLayoutMediator(binding.tabDots, binding.vpAdImages, (tab, position) -> {}).attach();
    }

    private void setupTextSlider(String title, List<String> chunks) {
        AdTextSliderAdapter adapter = new AdTextSliderAdapter(title, chunks);
        binding.vpAdText.setAdapter(adapter);
        binding.vpAdText.setUserInputEnabled(false); // Only image slider controls text
    }

    private void setupActionButtons(JSONObject content) {
        // WhatsApp
        String cta = content.optString("cta", "");
        if (!cta.isEmpty()) {
            binding.ivActionWhatsapp.setVisibility(View.VISIBLE);
            binding.ivActionWhatsapp.setOnClickListener(v -> openUrl(cta));
        }

        // Google Maps
        String maps = content.optString("maps", "");
        if (!maps.isEmpty()) {
            binding.ivActionMap.setVisibility(View.VISIBLE);
            binding.ivActionMap.setOnClickListener(v -> openUrl(maps));
        }

        // Website
        String web = content.optString("website", "");
        if (!web.isEmpty()) {
            binding.ivActionWebsite.setVisibility(View.VISIBLE);
            binding.ivActionWebsite.setOnClickListener(v -> openUrl(web));
        }

        // Phone
        String call = content.optString("call", "");
        if (call.isEmpty()) call = content.optString("phone", ""); // Support legacy or new key
        if (!call.isEmpty()) {
            final String phoneNum = call;
            binding.ivActionCall.setVisibility(View.VISIBLE);
            binding.ivActionCall.setOnClickListener(v -> {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phoneNum)));
            });
        }

        // Instagram
        String insta = content.optString("instagram", "");
        if (!insta.isEmpty()) {
            binding.ivActionInstagram.setVisibility(View.VISIBLE);
            binding.ivActionInstagram.setOnClickListener(v -> openUrl(insta));
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    /**
     * Internal Adapter for the Synchronized Text Slider.
     */
    private class AdTextSliderAdapter extends RecyclerView.Adapter<AdTextSliderAdapter.TextHolder> {
        private final String title;
        private final List<String> chunks;

        AdTextSliderAdapter(String title, List<String> chunks) { this.title = title; this.chunks = chunks; }

        @NonNull
        @Override
        public TextHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new TextHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TextHolder holder, int position) {
            TextView tvTitle = holder.itemView.findViewById(android.R.id.text1);
            TextView tvDesc = holder.itemView.findViewById(android.R.id.text2);
            
            tvTitle.setText(title);
            tvTitle.setTextColor(0xFFFFFFFF);
            tvTitle.setTextSize(20);
            
            tvDesc.setText(chunks.get(position));
            tvDesc.setTextColor(0xFFBDBDBD);
            tvDesc.setTextSize(14);
        }

        @Override
        public int getItemCount() { return chunks.size(); }

        class TextHolder extends RecyclerView.ViewHolder {
            TextHolder(@NonNull View itemView) { super(itemView); }
        }
    }

    /**
     * Internal Adapter for the Image ViewPager slider.
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
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return new ViewHolder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String url = urls.get(position);
            ImageView targetView = (ImageView) holder.itemView;
            fetchAndDecryptMedia(url, targetView);
        }

        private void fetchAndDecryptMedia(String url, ImageView imageView) {
            String safeUrl = url;
            if (safeUrl != null && safeUrl.startsWith("ipfs://")) {
                safeUrl = safeUrl.replace("ipfs://", "https://cloudflare-ipfs.com/ipfs/");
            }

            Request request = new Request.Builder().url(safeUrl).build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
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
                        byte[] finalImageData;
                        if (!adDecryptionKeyHex.isEmpty()) {
                            byte[] aesKey = EncryptionUtils.hexToBytes(adDecryptionKeyHex);
                            finalImageData = EncryptionUtils.decrypt(encryptedBytes, aesKey);
                        } else {
                            finalImageData = encryptedBytes;
                        }
                        runOnUiThread(() -> {
                            ImageRequest imageRequest = new ImageRequest.Builder(AdPopupActivity.this)
                                    .data(finalImageData)
                                    .crossfade(true)
                                    .target(imageView)
                                    .build();
                            Coil.imageLoader(AdPopupActivity.this).enqueue(imageRequest);
                        });
                    } catch (Exception e) {
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