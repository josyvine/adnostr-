package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NEW: THE HIERARCHICAL RE-PUBLISH QUEUE
 * Role: Orchestrator for Sequential Network Healing.
 * Logic: Manages the timing and order of metadata broadcasts to ensure 
 * parent-child relationships (Category -> Sub -> Spec -> Brand) are indexed correctly.
 * 
 * This class prevents "Relay Flooding" and ensures that every re-broadcasted 
 * archived item receives a fresh cryptographic signature.
 * 
 * VOLATILITY & DROPDOWN FIX (NEW):
 * - Context Preservation: Ensures that when re-signing "Audi" or "Bajaj", the 
 *   category "Cars" or "Bikes" is strictly maintained in the tags and content.
 * - Forensic Feedback: Detailed trace logging added to monitor tiered indexing.
 * - REPAIR UPDATE: Implemented TechnicalLogListener to feed the forensic console.
 * - TIER 2 RECOVERY: Fixed logic in prepareArchive to ensure Technical Fields are never skipped.
 * - SPAM PREVENTION: Implemented internal deduplication filter to clean bloated archives.
 * 
 * 4-TIER HIERARCHY UPDATE:
 * - Tier 1: Main Category
 * - Tier 2: Sub Category
 * - Tier 3: Tech Spec Fields (The Anchors)
 * - Tier 4: Value Pools (Bajaj/Models/Years)
 */
public class SequentialBroadcastQueue {

    private static final String TAG = "AdNostr_Queue";

    // 4-TIER DEFINITIONS
    private static final int TIER_1_MAIN = 1;
    private static final int TIER_2_SUB = 2;
    private static final int TIER_3_FIELD = 3;
    private static final int TIER_4_VALUE = 4;

    private final Context context;
    private final AdNostrDatabaseHelper db;
    private final WebSocketClientManager wsManager;
    private final Handler queueHandler;

    // Internal lists for 4-tier sorting
    private final List<JSONObject> tier1Main = new ArrayList<>();
    private final List<JSONObject> tier2Sub = new ArrayList<>();
    private final List<JSONObject> tier3Field = new ArrayList<>();
    private final List<JSONObject> tier4Value = new ArrayList<>();

    private MarketplaceSchemaManager.TechnicalLogListener logListener;

    /**
     * UPDATED: Now accepts the MarketplaceSchemaManager version of the listener
     * to resolve the type mismatch error.
     */
    public void setTechnicalLogListener(MarketplaceSchemaManager.TechnicalLogListener listener) {
        this.logListener = listener;
    }

    public SequentialBroadcastQueue(Context context) {
        this.context = context.getApplicationContext();
        this.db = AdNostrDatabaseHelper.getInstance(context);
        this.wsManager = WebSocketClientManager.getInstance();
        this.queueHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Helper to safely dispatch logs to the listener.
     */
    private void sendForensicLog(String msg) {
        if (logListener != null) {
            logListener.onLogGenerated(msg);
        }
    }

    /**
     * Logic: Accepts the full forensic archive and sorts it into 4 dependency tiers.
     * REPAIR UPDATE: Implemented Defensive Parsing to skip ghost frames.
     * 4-TIER FIX: Correctly maps Main, Sub, Fields, and Values.
     */
    public boolean prepareArchive(String archiveJson) {
        // CRITICAL FIX: Check for empty strings
        if (archiveJson == null || archiveJson.trim().isEmpty()) {
            sendForensicLog("SYSTEM: Archive input is empty. Skipping preparation.");
            return false;
        }

        try {
            JSONArray archive = new JSONArray(archiveJson);
            tier1Main.clear();
            tier2Sub.clear();
            tier3Field.clear();
            tier4Value.clear();

            // Internal set for session deduplication
            Set<String> contentFingerprints = new HashSet<>();

            for (int i = 0; i < archive.length(); i++) {
                try {
                    Object item = archive.get(i);
                    if (!(item instanceof JSONObject)) continue;
                    JSONObject event = (JSONObject) item;

                    String contentStr = event.optString("content", "").trim();
                    if (contentStr.isEmpty() || !contentStr.startsWith("{")) {
                        continue; 
                    }

                    JSONObject content = new JSONObject(contentStr);
                    int kind = event.getInt("kind");

                    if (kind == 30006) {
                        String metadataType = content.optString("type", "").trim().toLowerCase();
                        String mainCat = content.optString("main", "").trim().toLowerCase();
                        String subCat = content.optString("sub", "").trim().toLowerCase();
                        String parentCat = content.optString("category", "").trim().toLowerCase();
                        String fieldLabel = content.optString("label", "").trim().toLowerCase();

                        // TIER 1: Main Category Logic
                        if ("category".equals(metadataType) && !mainCat.isEmpty()) {
                            if (contentFingerprints.add("tier1:" + mainCat)) {
                                tier1Main.add(event);
                            }
                        }

                        // TIER 2: Sub Category Logic
                        if ("category".equals(metadataType) && !subCat.isEmpty()) {
                            if (contentFingerprints.add("tier2:" + subCat)) {
                                tier2Sub.add(event);
                            }
                        }

                        // TIER 3: Tech Spec Fields Logic (The Anchors)
                        if ("field".equals(metadataType) && !fieldLabel.isEmpty()) {
                            // Anchor is unique per Sub-Category
                            if (contentFingerprints.add("tier3:" + parentCat + ":" + fieldLabel)) {
                                tier3Field.add(event);
                            }
                        }
                    } else if (kind == 30007) {
                        // TIER 4: Value Pools (Bajaj, Pulsar, etc.)
                        String cat = content.optString("category", "").trim().toLowerCase();
                        JSONObject specs = content.optJSONObject("specs");
                        String specsStr = (specs != null) ? specs.toString() : "";

                        if (contentFingerprints.add("tier4:" + cat + ":" + specsStr)) {
                            tier4Value.add(event);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed frame at index " + i);
                    continue;
                }
            }

            Log.d(TAG, "4-Tier Queue Prepared: T1=" + tier1Main.size() + ", T2=" + tier2Sub.size() + ", T3=" + tier3Field.size() + ", T4=" + tier4Value.size());

            // Forensic feedback for the Admin Console
            sendForensicLog("4-TIER MAP: Main=" + tier1Main.size() + " | Sub=" + tier2Sub.size() + " | Fields=" + tier3Field.size() + " | Values=" + tier4Value.size());

            if (tier1Main.isEmpty() && tier2Sub.isEmpty() && tier3Field.isEmpty() && tier4Value.isEmpty()) {
                sendForensicLog("SYSTEM: No valid unique metadata found.");
                return false;
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Queue preparation failed: " + e.getMessage());
            sendForensicLog("ERROR: Archive sorting failed - " + e.getMessage());
            return false;
        }
    }

    /**
     * Logic: Starts the cascading re-broadcast with 3-second timing delays.
     */
    public void startBroadcast() {
        sendForensicLog("ORDER: Executing 4-Tier Hierarchical Publish...");
        processTier(TIER_1_MAIN);
    }

    private void processTier(int tier) {
        List<JSONObject> targetList;
        int nextTier;

        switch (tier) {
            case TIER_1_MAIN:
                targetList = tier1Main;
                nextTier = TIER_2_SUB;
                break;
            case TIER_2_SUB:
                targetList = tier2Sub;
                nextTier = TIER_3_FIELD;
                break;
            case TIER_3_FIELD:
                targetList = tier3Field;
                nextTier = TIER_4_VALUE;
                break;
            case TIER_4_VALUE:
                targetList = tier4Value;
                nextTier = -1; // End of chain
                break;
            default:
                return;
        }

        if (targetList.isEmpty()) {
            if (nextTier != -1) {
                sendForensicLog("ORDER: Tier " + tier + " empty. Skipping to next.");
                processTier(nextTier);
            }
            return;
        }

        Log.i(TAG, "Processing Tier " + tier + ": Sending " + targetList.size() + " items.");
        sendForensicLog("ORDER: Commencing Tier " + tier + " (" + targetList.size() + " items)");

        for (int i = 0; i < targetList.size(); i++) {
            final JSONObject originalEvent = targetList.get(i);
            final boolean isLastInTier = (i == targetList.size() - 1);
            final int nextTierToRun = nextTier;

            // Delay each item broadcast slightly to prevent relay rejection
            queueHandler.postDelayed(() -> {
                reSignAndSend(originalEvent);

                // HIERARCHY FIX: Force 3-second delay between Tiers
                if (isLastInTier && nextTierToRun != -1) {
                    sendForensicLog("ORDER: Tier " + tier + " complete. Waiting 3s for network indexing...");
                    queueHandler.postDelayed(() -> processTier(nextTierToRun), 3000);
                } else if (isLastInTier && nextTierToRun == -1) {
                    sendForensicLog("\n=== 4-TIER HEALING SEQUENCE COMPLETE ===");
                }
            }, i * 500L);
        }
    }

    /**
     * Logic: Generates a fresh BIP-340 signature with the CURRENT timestamp.
     */
    private void reSignAndSend(JSONObject oldEvent) {
        try {
            JSONObject newEvent = new JSONObject();
            int kind = oldEvent.getInt("kind");
            String contentStr = oldEvent.getString("content");
            JSONObject content = new JSONObject(contentStr);

            String label = content.optString("main", content.optString("sub", content.optString("label", content.optString("category", "Unknown"))));
            sendForensicLog("HEAL: Re-signing " + kind + " for '" + label + "'");

            newEvent.put("kind", kind);
            newEvent.put("pubkey", db.getPublicKey());
            newEvent.put("created_at", System.currentTimeMillis() / 1000); 
            newEvent.put("content", contentStr);
            newEvent.put("tags", oldEvent.getJSONArray("tags"));

            JSONObject signed = NostrEventSigner.signHealedEvent(db.getPrivateKey(), newEvent);
            if (signed != null) {
                wsManager.broadcastEvent(signed.toString());
                sendForensicLog("CRYPTO: BIP-340 Proof OK. Broadcasted.");
            } else {
                sendForensicLog("CRYPTO: FAILED to sign archived item.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Re-sign failure: " + e.getMessage());
            sendForensicLog("ERROR: Re-signing exception - " + e.getMessage());
        }
    }
}