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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import coil.Coil;
import coil.request.ImageRequest;

/**
 * Professional Ad Delivery Overlay.
 * FIXED: Implemented ViewPager2 Adapter for "Slide to left or right" image viewing.
 * FIXED: Supports both single image (String) and multiple images (JSONArray) in Ad content.
 * FIXED: Removed generic Toast. Pipes raw Java stack traces to ErrorDisplayActivity.
 */
public class AdPopupActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdPopup";
    private ActivityAdPopupBinding binding;

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
            // CRITICAL: Extract raw Java exception for the diagnostic screen
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

        // FIXED: Handle Image Slider (Supports String or Array)
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
     */
    private class ImageSliderAdapter extends RecyclerView.Adapter<ImageSliderAdapter.ViewHolder> {
        private final List<String> images;

        ImageSliderAdapter(List<String> images) { this.images = images; }

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
            String url = images.get(position).replace("ipfs://", "https://cloudflare-ipfs.com/ipfs/");
            ImageRequest request = new ImageRequest.Builder(AdPopupActivity.this)
                    .data(url)
                    .crossfade(true)
                    .target((ImageView) holder.itemView)
                    .build();
            Coil.imageLoader(AdPopupActivity.this).enqueue(request);
        }

        @Override
        public int getItemCount() { return images.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(@NonNull View itemView) { super(itemView); }
        }
    }
}