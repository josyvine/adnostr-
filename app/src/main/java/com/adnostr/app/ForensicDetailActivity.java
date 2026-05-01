package com.adnostr.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityForensicDetailBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * NEW: Forensic Deep-Dive Engine.
 * - Role: Renders the full contents of a crowdsourced card vertically.
 * - Governance: Allows surgical selection and deletion of specific models/years.
 * - Logic: Kind 30007 partial updates (Nuke old, broadcast corrected new).
 */
public class ForensicDetailActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_ForensicDetail";
    private ActivityForensicDetailBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;

    private JSONObject originalEvent;
    private String categoryContext = "";
    private String fieldName = "";
    private List<String> valueList = new ArrayList<>();
    private ForensicValueAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Immersive UI Setup
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);

        binding = ActivityForensicDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        // 2. Extract Data from Intent
        String rawJson = getIntent().getStringExtra("EVENT_JSON");
        if (rawJson == null) {
            finish();
            return;
        }

        try {
            originalEvent = new JSONObject(rawJson);
            parseForensicContent();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse forensic detail: " + e.getMessage());
            Toast.makeText(this, "Corrupted Event Data", Toast.LENGTH_SHORT).show();
            finish();
        }

        // 3. UI Listeners
        binding.btnBackDetail.setOnClickListener(v -> finish());
        
        binding.cbSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (adapter != null) {
                adapter.selectAll(isChecked);
            }
        });

        binding.btnNukeSelected.setOnClickListener(v -> executeSurgicalPurge());
    }

    /**
     * Extracts Bajaj Models/Years into a clean vertical list.
     */
    private void parseForensicContent() throws Exception {
        String contentStr = originalEvent.getString("content");
        JSONObject content = new JSONObject(contentStr);
        int kind = originalEvent.getInt("kind");

        if (kind == 30007) {
            // VALUE POOL: Bajaj Models
            JSONObject specs = content.optJSONObject("specs");
            JSONObject contextObj = content.optJSONObject("context");
            categoryContext = (contextObj != null) ? contextObj.optString("value", "Global") : "General";

            if (specs != null && specs.length() > 0) {
                Iterator<String> keys = specs.keys();
                fieldName = keys.next();
                
                Object valObj = specs.get(fieldName);
                if (valObj instanceof JSONArray) {
                    JSONArray arr = (JSONArray) valObj;
                    for (int i = 0; i < arr.length(); i++) {
                        valueList.add(arr.getString(i));
                    }
                } else {
                    // Single string fallback (comma separated)
                    String raw = valObj.toString();
                    for (String s : raw.split(",")) {
                        if (!s.trim().isEmpty()) valueList.add(s.trim());
                    }
                }
            }
        } else {
            // CATEGORY/FIELD definitions
            fieldName = kind == 30006 ? content.optString("label", "Field Definition") : "Category Definition";
            categoryContext = content.optString("category", "Registry");
            valueList.add("Definition: " + fieldName);
            valueList.add("Author: " + originalEvent.optString("pubkey"));
            binding.cbSelectAll.setVisibility(View.GONE);
        }

        // Setup Header
        binding.tvDetailTitle.setText(categoryContext);
        binding.tvDetailSubtitle.setText(fieldName);

        setupRecyclerView();
    }

    private void setupRecyclerView() {
        binding.rvValueList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ForensicValueAdapter(valueList, count -> {
            binding.btnNukeSelected.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            binding.btnNukeSelected.setText("PURGE SELECTED (" + count + ")");
        });
        binding.rvValueList.setAdapter(adapter);
    }

    /**
     * Logic: Purges specific models while preserving the rest.
     */
    private void executeSurgicalPurge() {
        List<String> toDelete = adapter.getSelectedValues();
        if (toDelete.isEmpty()) return;

        try {
            // 1. Identify remaining data
            List<String> remaining = new ArrayList<>(valueList);
            remaining.removeAll(toDelete);

            // 2. Broadcast Kind 5 (Nuke the old Bajaj card)
            broadcastWipe(originalEvent.getString("id"));

            // 3. If partial data remains, broadcast a corrected Kind 30007
            if (!remaining.isEmpty() && originalEvent.getInt("kind") == 30007) {
                broadcastCorrectedValues(remaining);
            }

            Toast.makeText(this, "Purge Complete. Syncing network...", Toast.LENGTH_LONG).show();
            finish();

        } catch (Exception e) {
            Log.e(TAG, "Purge failed: " + e.getMessage());
        }
    }

    private void broadcastWipe(String eventId) {
        try {
            db.addWipedSchemaId(eventId);
            JSONObject delEvent = new JSONObject();
            delEvent.put("kind", 5);
            delEvent.put("pubkey", db.getPublicKey());
            delEvent.put("created_at", System.currentTimeMillis() / 1000);
            delEvent.put("content", "Forensic Item Removal");

            JSONArray tags = new JSONArray();
            tags.put(new JSONArray().put("e").put(eventId));
            delEvent.put("tags", tags);

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), delEvent);
            if (signed != null) {
                wsManager.broadcastEvent(signed.toString());
            }
        } catch (Exception ignored) {}
    }

    private void broadcastCorrectedValues(List<String> remaining) {
        try {
            JSONObject content = new JSONObject();
            content.put("category", new JSONObject(originalEvent.getString("content")).getString("category"));
            
            JSONObject specs = new JSONObject();
            JSONArray vals = new JSONArray();
            for (String s : remaining) vals.put(s);
            specs.put(fieldName.toLowerCase(), vals);
            content.put("specs", specs);

            JSONObject contextObj = new JSONObject();
            contextObj.put("field", "brand"); // Bajaj is usually a brand context
            contextObj.put("value", categoryContext);
            content.put("context", contextObj);

            // Use the MarketplaceSchemaManager to publish the update
            MarketplaceSchemaManager.broadcastBulkValues(
                    this, 
                    content.getString("category"), 
                    fieldName.toLowerCase(), 
                    remaining.toString().replace("[","").replace("]",""),
                    "brand", 
                    categoryContext
            );
        } catch (Exception ignored) {}
    }
}