package com.adnostr.app;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityReportBinding;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * ADMIN SUPREMACY: Forensic Report Engine.
 * - Theme: Dark Control Room Mode.
 * - Logic: Monitors Kind 30006 and 30007 globally.
 * - Feed: Strictly chronological (Newest first).
 * - Governance: Triggers cascading wipes and surgical Kind 5 deletions.
 * 
 * CROWDSOURCED DATA FIX:
 * - Local Hide: Integrated Dismissal logic to clear cards locally without network wipes.
 * - Persistence: Blocks dismissed IDs from re-entering the feed during active sessions.
 * 
 * VOLATILITY FIX:
 * - Immutable Aggregation: Now loads data from the Hard-Locked Forensic Archive on startup.
 * - Network Healing: Integrated Refresh icon to trigger the Hourly Sequential Re-Publisher manually.
 */
public class ReportActivity extends AppCompatActivity implements WebSocketClientManager.SchemaEventListener {

    private static final String TAG = "AdNostr_Forensic";
    private ActivityReportBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    
    private final List<JSONObject> fullMasterList = new ArrayList<>();
    private final List<JSONObject> displayList = new ArrayList<>();
    private ReportAdapter adapter;
    
    private String currentFilter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Immersive Control Room UI Setup
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        
        binding = ActivityReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        // Security Check: Unauthorized access shutdown
        if (!db.isAdmin()) {
            Toast.makeText(this, "ACCESS DENIED: Administrative Privileges Required.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupToolbar();
        setupRecyclerView();
        setupFilterRibbon();

        // =========================================================================
        // VOLATILITY FIX: AGGREGATE PERMANENT ARCHIVE
        // Before scanning the network, load every crowdsourced item we have saved locally.
        // =========================================================================
        loadArchiveToConsole();

        // 2. Initiate Global Discovery Scan
        fetchGlobalContributions();

        // AUTO-CLICK LOGIC: Automatically trigger a network heal when Admin enters
        MarketplaceSchemaManager.executeSequentialHealing(this);
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarReport);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Forensic Console");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarReport.setNavigationOnClickListener(v -> finish());
    }

    /**
     * VOLATILITY FIX: Pulls data from the Hard-Locked archive so the console is 
     * never empty, even if the network was just wiped.
     */
    private void loadArchiveToConsole() {
        try {
            String archiveJson = db.getForensicArchive();
            JSONArray archiveArray = new JSONArray(archiveJson);
            
            for (int i = 0; i < archiveArray.length(); i++) {
                JSONObject event = archiveArray.getJSONObject(i);
                String id = event.getString("id");

                // Skip if dismissed locally or wiped globally
                if (db.isReportDismissed(id) || db.isSchemaWiped(id)) continue;

                fullMasterList.add(event);
            }
            
            Log.i(TAG, "Console populated with " + fullMasterList.size() + " archive entries.");
            applyFilter();

        } catch (Exception e) {
            Log.e(TAG, "Archive Load Error: " + e.getMessage());
        }
    }

    /**
     * UPDATED: Added listener for local dismissal (Mark as Read).
     */
    private void setupRecyclerView() {
        binding.rvForensicFeed.setLayoutManager(new LinearLayoutManager(this));
        
        // The adapter handles the complex card logic and Admin-only trash icons
        adapter = new ReportAdapter(displayList, db.getReportLastSeen(), new ReportAdapter.OnPurgeListener() {
            @Override
            public void onSurgicalWipe(JSONObject event) {
                executeSurgicalWipe(event);
            }

            @Override
            public void onCascadingNuke(String categoryName) {
                executeCascadingNuke(categoryName);
            }

            @Override
            public void onLocalDismiss(JSONObject event) {
                // NEW: Logic to hide the card locally without global deletion
                executeLocalDismiss(event);
            }
        });
        
        binding.rvForensicFeed.setAdapter(adapter);
    }

    /**
     * Logic: Horizontal Chip Ribbon for real-time feed filtering.
     */
    private void setupFilterRibbon() {
        binding.chipGroupFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            
            Chip chip = findViewById(checkedIds.get(0));
            String filterText = chip.getText().toString().toUpperCase();
            
            if (filterText.contains("ALL")) currentFilter = "ALL";
            else if (filterText.contains("CATEGORIES")) currentFilter = "CATEGORY";
            else if (filterText.contains("TECH SPECS")) currentFilter = "FIELD";
            else if (filterText.contains("BRANDS")) currentFilter = "VALUE";
            
            applyFilter();
        });
    }

    /**
     * Logic: Broadcasts a REQ to all relays for schema contributions.
     */
    private void fetchGlobalContributions() {
        binding.pbForensicLoading.setVisibility(View.VISIBLE);
        wsManager.addSchemaListener(this);

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30006).put(30007));
            
            String subId = "forensic-" + UUID.randomUUID().toString().substring(0, 4);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();
            
            wsManager.connectPool(db.getRelayPool());
            wsManager.broadcastEvent(req); // Manual trigger for connected relays

        } catch (Exception e) {
            Log.e(TAG, "Discovery Scan Failed: " + e.getMessage());
        }
    }

    /**
     * Interface: Called by WebSocketClientManager when Kind 30006/30007 arrives.
     * FIXED: Now checks for local dismissal to prevent Bajaj cards from returning.
     */
    @Override
    public void onSchemaEventReceived(String url, JSONObject event) {
        runOnUiThread(() -> {
            try {
                String eventId = event.getString("id");
                
                // Prevent duplicate entries in the forensic list
                for (JSONObject existing : fullMasterList) {
                    if (existing.getString("id").equals(eventId)) return;
                }

                // Check blocklist before adding
                if (db.isSchemaWiped(eventId)) return;

                // =========================================================================
                // LOCAL PERSISTENCE CHECK (NEW)
                // If the Admin has marked this card as read, do not display it.
                // =========================================================================
                if (db.isReportDismissed(eventId)) {
                    return;
                }

                fullMasterList.add(event);
                
                // VOLATILITY FIX: Lock any newly discovered network metadata to the archive
                db.saveToForensicArchive(event.toString());
                
                // Sort Descending (Newest at top)
                Collections.sort(fullMasterList, (a, b) -> 
                    Long.compare(b.optLong("created_at"), a.optLong("created_at")));

                applyFilter();
                binding.pbForensicLoading.setVisibility(View.GONE);
                binding.llEmptyForensic.setVisibility(fullMasterList.isEmpty() ? View.VISIBLE : View.GONE);

            } catch (Exception ignored) {}
        });
    }

    private void applyFilter() {
        displayList.clear();
        for (JSONObject event : fullMasterList) {
            try {
                String contentStr = event.getString("content");
                JSONObject content = new JSONObject(contentStr);
                int kind = event.getInt("kind");
                
                if (currentFilter.equals("ALL")) {
                    displayList.add(event);
                } else if (currentFilter.equals("CATEGORY")) {
                    if (kind == 30006 && "category".equals(content.optString("type"))) displayList.add(event);
                } else if (currentFilter.equals("FIELD")) {
                    if (kind == 30006 && "field".equals(content.optString("type"))) displayList.add(event);
                } else if (currentFilter.equals("VALUE")) {
                    if (kind == 30007) displayList.add(event);
                }
            } catch (Exception ignored) {}
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * ADMIN SUPREMACY: Wipes a single Tech Spec or Brand Value from the network.
     */
    private void executeSurgicalWipe(JSONObject event) {
        try {
            String id = event.getString("id");
            db.addWipedSchemaId(id);
            
            // Broadcast Kind 5 Deletion
            List<String> idList = new ArrayList<>();
            idList.add(id);
            broadcastWipe(idList, null);
            
            fullMasterList.remove(event);
            applyFilter();
            Toast.makeText(this, "Item Purged.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Wipe failed: " + e.getMessage());
        }
    }

    /**
     * ADMIN SUPREMACY: Cascading Nuke. Wipes a Category and every linked sub-item.
     */
    private void executeCascadingNuke(String categoryName) {
        List<String> idsToWipe = new ArrayList<>();
        List<JSONObject> itemsToRemove = new ArrayList<>();

        for (JSONObject event : fullMasterList) {
            try {
                String contentStr = event.getString("content");
                JSONObject content = new JSONObject(contentStr);
                
                boolean isTarget = categoryName.equals(content.optString("sub")) || 
                                  categoryName.equals(content.optString("category"));
                
                if (isTarget) {
                    String eid = event.getString("id");
                    idsToWipe.add(eid);
                    itemsToRemove.add(event);
                    db.addWipedSchemaId(eid);
                }
            } catch (Exception ignored) {}
        }

        if (!idsToWipe.isEmpty()) {
            broadcastWipe(idsToWipe, categoryName);
            fullMasterList.removeAll(itemsToRemove);
            applyFilter();
            Toast.makeText(this, "Category Tree Wiped Successfully.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * NEW: Executes the local "Mark as Read" logic.
     * Removes card from UI and saves the ID so it doesn't return, 
     * but does NOT send a global delete command.
     */
    private void executeLocalDismiss(JSONObject event) {
        try {
            String id = event.getString("id");
            db.addDismissedReportId(id);
            fullMasterList.remove(event);
            applyFilter();
            Toast.makeText(this, "Card Dismissed (Local Only).", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Dismiss failed: " + e.getMessage());
        }
    }

    private void broadcastWipe(List<String> ids, String hardcodedName) {
        try {
            JSONObject delEvent = new JSONObject();
            delEvent.put("kind", 5);
            delEvent.put("pubkey", db.getPublicKey());
            delEvent.put("created_at", System.currentTimeMillis() / 1000);
            delEvent.put("content", "Administrative Schema Cleanup");

            JSONArray tags = new JSONArray();
            for (String id : ids) {
                tags.put(new JSONArray().put("e").put(id));
            }
            if (hardcodedName != null) {
                tags.put(new JSONArray().put("hardcoded_name").put(hardcodedName));
                db.addHiddenHardcodedName(hardcodedName);
            }
            delEvent.put("tags", tags);

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), delEvent);
            if (signed != null) {
                wsManager.broadcastEvent(signed.toString());
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // VOLATILITY FIX: RE-PUBLISH MENU LOGIC
    // =========================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate a standard refresh icon into the toolbar
        MenuItem refreshItem = menu.add(Menu.NONE, 1001, Menu.NONE, "Heal Network");
        refreshItem.setIcon(android.R.drawable.stat_notify_sync);
        refreshItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1001) {
            Toast.makeText(this, "Initiating Sequential Network Healing...", Toast.LENGTH_SHORT).show();
            // Manually trigger the tiered re-broadcast
            MarketplaceSchemaManager.executeSequentialHealing(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        // ADMIN SUPREMACY: Reset notification state on exit
        db.saveReportLastSeen();
        wsManager.removeSchemaListener(this);
        super.onDestroy();
    }
}