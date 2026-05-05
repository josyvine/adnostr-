package com.adnostr.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.ActivityArchiveBinding;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * THE TRUTH ANCHOR: Memory Archive for Advertiser B.
 * - Role: Read-Only mirror of the Forensic Console.
 * - Purpose: Provides every advertiser visibility into their hard-locked data.
 * - Hierarchy: Implements the 4-Tier logic (T1, T2, T3, T4).
 * - Healing: Allows Advertiser B to restore the network memory via Sequential Healing.
 */
public class ArchiveActivity extends AppCompatActivity implements WebSocketClientManager.SchemaEventListener {

    private static final String TAG = "AdNostr_ArchiveUI";
    private ActivityArchiveBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;

    private final List<JSONObject> fullArchiveList = Collections.synchronizedList(new ArrayList<>());
    private final List<JSONObject> displayList = Collections.synchronizedList(new ArrayList<>());
    private ArchiveAdapter adapter;

    private String currentFilter = "ALL";

    // PERFORMANCE ENGINE: UI Debouncing
    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private boolean isUpdatePending = false;
    private final Runnable uiRefreshRunnable = this::applyFilterAndNotify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Dark Immersive Theme
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);

        binding = ActivityArchiveBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();

        setupToolbar();
        setupRecyclerView();
        setupFilterRibbon();

        // 2. Load Local Hard-Locked Anchor
        new Thread(this::loadLocalAnchorToView).start();

        // 3. Register real-time sniffer
        wsManager.addSchemaListener(this);
        
        Log.i(TAG, "Memory Archive Initialized for Advertiser Node.");
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarArchive);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Local Memory Archive");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarArchive.setNavigationOnClickListener(v -> finish());
    }

    /**
     * Pulls the 4-tier data from the Immutable Forensic Archive.
     */
    private void loadLocalAnchorToView() {
        try {
            String archiveJson = db.getForensicArchive();
            JSONArray archiveArray = new JSONArray(archiveJson);

            synchronized (fullArchiveList) {
                for (int i = 0; i < archiveArray.length(); i++) {
                    try {
                        Object item = archiveArray.get(i);
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject event = (JSONObject) item;
                        
                        String id = event.optString("id", "");
                        if (id.isEmpty()) continue;

                        boolean exists = false;
                        for (JSONObject existing : fullArchiveList) {
                            if (existing.optString("id").equals(id)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) fullArchiveList.add(event);
                    } catch (Exception ignored) {}
                }
            }

            requestBatchUpdate();

        } catch (Exception e) {
            Log.e(TAG, "Archive view load failed: " + e.getMessage());
        }
    }

    private void setupRecyclerView() {
        binding.rvArchiveFeed.setLayoutManager(new LinearLayoutManager(this));
        // ArchiveAdapter is read-only; no purge listeners passed
        adapter = new ArchiveAdapter(displayList);
        binding.rvArchiveFeed.setAdapter(adapter);
    }

    private void setupFilterRibbon() {
        binding.chipGroupArchiveFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;

            Chip chip = findViewById(checkedIds.get(0));
            String filterText = chip.getText().toString().toUpperCase();

            if (filterText.contains("ALL")) currentFilter = "ALL";
            else if (filterText.contains("TIER 1")) currentFilter = "TIER_1";
            else if (filterText.contains("TIER 2")) currentFilter = "TIER_2";
            else if (filterText.contains("TECH SPECS")) currentFilter = "TECH_SPECS";
            else if (filterText.contains("VALUES")) currentFilter = "VALUES";

            requestBatchUpdate();
        });
    }

    @Override
    public void onSchemaEventReceived(String url, JSONObject event) {
        try {
            String eventId = event.optString("id", "");
            if (eventId.isEmpty()) return;

            synchronized (fullArchiveList) {
                for (JSONObject existing : fullArchiveList) {
                    if (existing.optString("id").equals(eventId)) return;
                }
                fullArchiveList.add(event);
            }
            requestBatchUpdate();
        } catch (Exception ignored) {}
    }

    private void requestBatchUpdate() {
        if (!isUpdatePending) {
            isUpdatePending = true;
            uiUpdateHandler.postDelayed(uiRefreshRunnable, 400);
        }
    }

    private void applyFilterAndNotify() {
        isUpdatePending = false;
        applyFilter();

        if (binding != null) {
            binding.pbArchiveLoading.setVisibility(View.GONE);
            binding.llEmptyArchive.setVisibility(displayList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * CORE LOGIC: 4-Tier Read-Only Filtering.
     */
    private void applyFilter() {
        synchronized (displayList) {
            displayList.clear();
            synchronized (fullArchiveList) {
                Collections.sort(fullArchiveList, (a, b) -> 
                    Long.compare(b.optLong("created_at"), a.optLong("created_at")));

                for (JSONObject event : fullArchiveList) {
                    try {
                        String contentStr = event.optString("content", "");
                        if (!contentStr.trim().startsWith("{")) continue;

                        JSONObject content = new JSONObject(contentStr);
                        int kind = event.getInt("kind");

                        if (currentFilter.equals("ALL")) {
                            displayList.add(event);
                        } 
                        else if (currentFilter.equals("TIER_1")) {
                            if (kind == 30006 && "category".equals(content.optString("type")) && content.has("main")) {
                                displayList.add(event);
                            }
                        } 
                        else if (currentFilter.equals("TIER_2")) {
                            if (kind == 30006 && "category".equals(content.optString("type")) && content.has("sub")) {
                                displayList.add(event);
                            }
                        }
                        else if (currentFilter.equals("TECH_SPECS")) {
                            if (kind == 30006 && "field".equals(content.optString("type"))) {
                                displayList.add(event);
                            }
                        } 
                        else if (currentFilter.equals("VALUES")) {
                            if (kind == 30007) {
                                displayList.add(event);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Universal Healing Icon for B
        MenuItem healItem = menu.add(Menu.NONE, 2001, Menu.NONE, "Restore Network");
        healItem.setIcon(android.R.drawable.stat_notify_sync);
        healItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 2001) {
            final StringBuilder healLogs = new StringBuilder();
            healLogs.append("=== INITIATING ADVERTISER-LED RESTORATION ===\n\n");
            healLogs.append("Scope: Broadcasting unique archive frames...\n");

            final RelayReportDialog report = RelayReportDialog.newInstance(
                    "TRUTH ANCHOR CONSOLE", 
                    "Restoring collective database...", 
                    healLogs.toString()
            );
            report.showSafe(getSupportFragmentManager(), "HEAL_B_LOG");

            MarketplaceSchemaManager.TechnicalLogListener healerListener = msg -> {
                runOnUiThread(() -> {
                    healLogs.append(msg).append("\n");
                    report.updateTechnicalLogs("Restoring Memory...", healLogs.toString());
                });
            };

            MarketplaceSchemaManager.executeSequentialHealing(this, healerListener);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        wsManager.removeSchemaListener(this);
        uiUpdateHandler.removeCallbacks(uiRefreshRunnable);
        super.onDestroy();
    }
}