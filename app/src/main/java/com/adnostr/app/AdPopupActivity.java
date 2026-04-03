package com.adnostr.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityAdPopupBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import coil.Coil;
import coil.ImageLoader;
import coil.request.ImageRequest;

/**
 * The Ad Delivery Overlay.
 * Displays a modern, sleek card for received decentralized ads.
 * Handles swipeable images (via Coil) and dynamic intent actions for business links.
 */
public class AdPopupActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdPopup";
    private ActivityAdPopupBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Transparent Overlay Flags
        // Allows the activity to show over the lock screen and dismiss the keyguard if needed
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // 2. Initialize ViewBinding
        binding = ActivityAdPopupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 3. Extract Ad Data from the incoming Intent
        // Background listener (NostrListenerWorker) sends the JSON payload here
        String adJsonString = getIntent().getStringExtra("AD_PAYLOAD_JSON");

        if (adJsonString == null || adJsonString.isEmpty()) {
            Log.e(TAG, "AdPopup launched without payload. Closing.");
            finish();
            return;
        }

        // 4. Parse and Display the Ad
        try {
            parseAndPopulateAd(adJsonString);
        } catch (Exception e) {
            Log.e(TAG, "Failed to render Ad UI: " + e.getMessage());
            // Global Crash Watcher will handle this if it's a fatal error
            throw new RuntimeException("Ad Rendering Failure", e);
        }

        // 5. Setup Close Button
        binding.btnCloseAd.setOnClickListener(v -> finish());
    }

    /**
     * Extracts fields from the kind:30001 event and updates the UI components.
     */
    private void parseAndPopulateAd(String jsonStr) throws Exception {
        JSONObject event = new JSONObject(jsonStr);
        JSONObject content = new JSONObject(event.getString("content"));

        // Set Text Content
        String title = content.optString("title", "Local Deal Found");
        String desc = content.optString("desc", "Click for details");
        binding.tvPopupTitle.setText(title);
        binding.tvPopupDesc.setText(desc);

        // Handle Images (IPFS Gateway Loading)
        JSONArray imageArray = content.optJSONArray("images");
        if (imageArray != null && imageArray.length() > 0) {
            // Get the first image as the primary cover
            String ipfsUri = imageArray.getString(0);
            String gatewayUrl = ipfsUri.replace("ipfs://", "https://cloudflare-ipfs.com/ipfs/");
            
            // Load image using Coil (Modern, lightweight)
            ImageRequest request = new ImageRequest.Builder(this)
                    .data(gatewayUrl)
                    .crossfade(true)
                    .target(binding.ivAdCover)
                    .build();
            
            ImageLoader imageLoader = Coil.imageLoader(this);
            imageLoader.enqueue(request);
        }

        // Handle Dynamic Action Buttons
        JSONObject links = content.optJSONObject("links");
        if (links != null) {
            setupActionButtons(links);
        }
    }

    /**
     * Logic to show/hide and configure buttons based on the advertiser's provided links.
     */
    private void setupActionButtons(JSONObject links) {
        // 1. WhatsApp Button
        String whatsapp = links.optString("whatsapp");
        if (!whatsapp.isEmpty()) {
            binding.btnActionWhatsapp.setVisibility(View.VISIBLE);
            binding.btnActionWhatsapp.setOnClickListener(v -> {
                String url = "https://wa.me/" + whatsapp.replaceAll("[^\\d]", "");
                openUrlIntent(url);
            });
        }

        // 2. Google Maps Button
        String maps = links.optString("maps");
        if (!maps.isEmpty()) {
            binding.btnActionMap.setVisibility(View.VISIBLE);
            binding.btnActionMap.setOnClickListener(v -> openUrlIntent(maps));
        }

        // 3. Website Button
        String website = links.optString("website");
        if (!website.isEmpty()) {
            binding.btnActionWebsite.setVisibility(View.VISIBLE);
            binding.btnActionWebsite.setOnClickListener(v -> openUrlIntent(website));
        }

        // 4. Call Now Button
        String call = links.optString("call");
        if (!call.isEmpty()) {
            binding.btnActionCall.setVisibility(View.VISIBLE);
            binding.btnActionCall.setOnClickListener(v -> {
                Intent callIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + call));
                startActivity(callIntent);
            });
        }
    }

    /**
     * Helper to open external links (Maps, Web, WhatsApp) via standard Android Intents.
     */
    private void openUrlIntent(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open link. App may be missing.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        // Standard close on back press
        super.onBackPressed();
        finish();
    }
}