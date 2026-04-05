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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import coil.Coil;
import coil.ImageLoader;
import coil.request.ImageRequest;

/**
 * The Ad Delivery Overlay.
 * UPDATED: Fixed parsing to match Kind 30001 Ad format spec.
 * FIXED: Image key changed to singular "image" and links moved to content root.
 * FIXED: Removed generic Toast error. Now pipes raw Java Exception stack traces directly to the ErrorDisplayActivity.
 */
public class AdPopupActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_AdPopup";
    private ActivityAdPopupBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Transparent Overlay Flags
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 2. Initialize ViewBinding
        binding = ActivityAdPopupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 3. Extract Ad Data from the incoming Intent
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
            
            // CRITICAL FIX: Extract the raw Java Stack Trace for the ErrorDisplayActivity
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String rawStackTrace = sw.toString();

            // Pass the raw error directly to your custom Error UI instead of showing a generic Toast
            // This allows you to identify exactly why the parser failed on a specific relay payload.
            Intent errorIntent = new Intent(this, ErrorDisplayActivity.class);
            errorIntent.putExtra("ERROR_DETAILS", "AdPopup Render Failure:\n\nPayload:\n" + adJsonString + "\n\nRaw Exception:\n" + rawStackTrace);
            errorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(errorIntent);

            finish();
        }

        // 5. Setup Close Button
        binding.btnCloseAd.setOnClickListener(v -> finish());
    }

    /**
     * Extracts fields from the Kind 30001 Ad event.
     * UPDATED: Aligned with Advertiser Step 2 JSON format requirements.
     */
    private void parseAndPopulateAd(String jsonStr) throws Exception {
        JSONObject event;

        // 1. Parse Nostr Relay Wrapper
        if (jsonStr.trim().startsWith("[")) {
            JSONArray relayMsg = new JSONArray(jsonStr);
            // Nostr EVENT messages are: ["EVENT", "sub_id", {event_object}]
            if (relayMsg.length() >= 3 && "EVENT".equals(relayMsg.getString(0))) {
                event = relayMsg.getJSONObject(2);
            } else {
                throw new Exception("Malformed Nostr relay message array.");
            }
        } else {
            event = new JSONObject(jsonStr);
        }

        // 2. Extract nested Ad 'content' string
        String contentRaw = event.optString("content", "");
        if (contentRaw.isEmpty()) throw new Exception("Ad event contains no content payload (Empty String).");

        // The 'content' in Kind 30001 is a stringified JSON object
        JSONObject content = new JSONObject(contentRaw);

        // 3. Set Text Content
        String title = content.optString("title", "");
        String desc = content.optString("desc", "");

        // If title is missing, the Ad is invalid for display. Throwing triggers the Error screen.
        if (title.isEmpty()) throw new Exception("Ad payload is missing the required 'title' field.");

        binding.tvPopupTitle.setText(title);
        binding.tvPopupDesc.setText(desc);

        // 4. Handle Image (IPFS Gateway Loading)
        // FIXED: Uses "image" key and ensures visibility is handled correctly.
        String ipfsUri = content.optString("image", "");
        if (!ipfsUri.isEmpty()) {
            String gatewayUrl = ipfsUri.replace("ipfs://", "https://cloudflare-ipfs.com/ipfs/");

            ImageRequest request = new ImageRequest.Builder(this)
                    .data(gatewayUrl)
                    .crossfade(true)
                    .target(binding.ivAdCover)
                    .build();

            Coil.imageLoader(this).enqueue(request);
            binding.ivAdCover.setVisibility(View.VISIBLE);
        } else {
            binding.ivAdCover.setVisibility(View.GONE);
        }

        // 5. Handle Dynamic Action Buttons
        // FIXED: cta and maps are now directly in the content root
        setupActionButtons(content);
    }

    private void setupActionButtons(JSONObject content) {
        // WhatsApp Button (cta)
        String ctaUrl = content.optString("cta", "");
        if (!ctaUrl.isEmpty()) {
            binding.btnActionWhatsapp.setVisibility(View.VISIBLE);
            binding.btnActionWhatsapp.setOnClickListener(v -> openUrlIntent(ctaUrl));
        }

        // Google Maps Button
        String mapsUrl = content.optString("maps", "");
        if (!mapsUrl.isEmpty()) {
            binding.btnActionMap.setVisibility(View.VISIBLE);
            binding.btnActionMap.setOnClickListener(v -> openUrlIntent(mapsUrl));
        }

        // Website Button (Fallback if cta is a standard website)
        String website = content.optString("website", "");
        if (!website.isEmpty()) {
            binding.btnActionWebsite.setVisibility(View.VISIBLE);
            binding.btnActionWebsite.setOnClickListener(v -> openUrlIntent(website));
        }

        // Call Now Button
        String call = content.optString("call", "");
        if (!call.isEmpty()) {
            binding.btnActionCall.setVisibility(View.VISIBLE);
            binding.btnActionCall.setOnClickListener(v -> {
                try {
                    Intent callIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + call));
                    startActivity(callIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "Phone app missing.", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

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
        super.onBackPressed();
        finish();
    }
}