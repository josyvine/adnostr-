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
 */
public class SequentialBroadcastQueue {

    private static final String TAG = "AdNostr_Queue";

    // TIER DEFINITIONS
    private static final int TIER_CATEGORY = 1;
    private static final int TIER_FIELD = 2;
    private static final int TIER_VALUE_POOL = 3;

    private final Context context;
    private final AdNostrDatabaseHelper db;
    private final WebSocketClientManager wsManager;
    private final Handler queueHandler;

    // Internal lists for tiered sorting
    private final List<JSONObject> catTier = new ArrayList<>();
    private final List<JSONObject> fieldTier = new ArrayList<>();
    private final List<JSONObject> valueTier = new ArrayList<>();

    /**
     * NEW: Interface to pipe technical traces back to the UI Activity.
     */
    public interface TechnicalLogListener {
        void onLogGenerated(String message);
    }

    private TechnicalLogListener logListener;

    public void setTechnicalLogListener(TechnicalLogListener listener) {
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
     * Logic: Accepts the full forensic archive and sorts it into dependency tiers.
     * REPAIR UPDATE: Implemented Defensive Parsing to skip ghost frames without crashing the queue.
     * FIXED: Explicitly identifies Tier 2 (Fields) to prevent dropdown dropout.
     * DEDUPLICATION: Internal set ensures only unique content frames are broadcasted to prevent relay spam blocks.
     */
    public boolean prepareArchive(String archiveJson) {
        // CRITICAL FIX: Check for empty strings to prevent "End of input at character 0"
        if (archiveJson == null || archiveJson.trim().isEmpty()) {
            sendForensicLog("SYSTEM: Archive input is empty. Skipping preparation.");
            return false;
        }

        try {
            JSONArray archive = new JSONArray(archiveJson);
            catTier.clear();
            fieldTier.clear();
            valueTier.clear();

            // Internal set for session deduplication (Prevents re-signing redundant categories)
            Set<String> contentFingerprints = new HashSet<>();

            for (int i = 0; i < archive.length(); i++) {
                JSONObject event = archive.getJSONObject(i);

                // REPAIR UPDATE: Verify content field before parsing
                String contentStr = event.optString("content", "").trim();

                // LOGIC: If the frame is a "ghost frame" (empty content), ignore and move to next valid item
                if (contentStr.isEmpty() || !contentStr.startsWith("{")) {
                    continue; 
                }

                try {
                    JSONObject content = new JSONObject(contentStr);
                    int kind = event.getInt("kind");

                    if (kind == 30006) {
                        String metadataType = content.optString("type", "").trim().toLowerCase();
                        String sub = content.optString("sub", "").trim().toLowerCase();
                        String cat = content.optString("category", "").trim().toLowerCase();
                        String label = content.optString("label", "").trim().toLowerCase();

                        // =========================================================================
                        // HIERARCHY REPAIR: TIER 1 vs TIER 2
                        // Separates Category (Bikes) from Fields (Brand/Model)
                        // =========================================================================
                        if ("category".equals(metadataType)) {
                            // Deduplicate based on sub-category name (e.g. Bikes)
                            if (contentFingerprints.add("cat:" + sub)) {
                                catTier.add(event);
                            }
                        } else if ("field".equals(metadataType) || content.has("label")) {
                            // TIER 2 RECOVERY: Captured Technical Field anchors (The Brand/Model/Year boxes)
                            // Deduplicate based on Category + Label (e.g. Bikes:Brand)
                            if (contentFingerprints.add("field:" + cat + ":" + label)) {
                                fieldTier.add(event);
                            }
                        }
                    } else if (kind == 30007) {
                        // =========================================================================
                        // TIER 3: VALUE POOLS (Bajaj, Pulsar, Discover, 2024, etc.)
                        // =========================================================================
                        String cat = content.optString("category", "").trim().toLowerCase();
                        JSONObject specs = content.optJSONObject("specs");
                        String specsStr = (specs != null) ? specs.toString() : "";

                        // Deduplicate value pools to stop relay spamming
                        if (contentFingerprints.add("val:" + cat + ":" + specsStr)) {
                            valueTier.add(event);
                        }
                    }
                } catch (Exception e) {
                    // Skip individual malformed items within the loop
                    Log.w(TAG, "Skipping malformed frame at index " + i);
                    continue;
                }
            }

            Log.d(TAG, "Queue Prepared: Tier1=" + catTier.size() + ", Tier2=" + fieldTier.size() + ", Tier3=" + valueTier.size());

            // Forensic feedback for the Admin Console
            sendForensicLog("TIER_MAP: Cat=" + catTier.size() + " | Field=" + fieldTier.size() + " | Value=" + valueTier.size());

            // Check if we actually found any valid items to restore after deduplication
            if (catTier.isEmpty() && fieldTier.isEmpty() && valueTier.isEmpty()) {
                sendForensicLog("SYSTEM: No valid unique metadata frames found in archive.");
                return false;
            }

            sendForensicLog("CLEANED: Archive optimized into " + (catTier.size() + fieldTier.size() + valueTier.size()) + " unique restoration frames.");
            return true;

        } catch (Exception e) {
            // Provide detailed length and snippet of the failing data for the user
            int dataLen = (archiveJson != null) ? archiveJson.length() : 0;
            String snippet = (archiveJson != null && dataLen > 50) ? archiveJson.substring(0, 50) : archiveJson;

            Log.e(TAG, "Queue preparation failed: " + e.getMessage());
            sendForensicLog("ERROR: Archive sorting failed - " + e.getMessage() + " (Len: " + dataLen + ", Snippet: " + snippet + ")");
            return false;
        }
    }

    /**
     * Logic: Starts the cascading re-broadcast with timing delays.
     */
    public void startBroadcast() {
        sendForensicLog("ORDER: Executing Hierarchical Publish sequence...");
        processTier(TIER_CATEGORY);
    }

    private void processTier(int tier) {
        List<JSONObject> targetList;
        int nextTier;

        switch (tier) {
            case TIER_CATEGORY:
                targetList = catTier;
                nextTier = TIER_FIELD;
                break;
            case TIER_FIELD:
                targetList = fieldTier;
                nextTier = TIER_VALUE_POOL;
                break;
            case TIER_VALUE_POOL:
                targetList = valueTier;
                nextTier = -1; // End of chain
                break;
            default:
                return;
        }

        if (targetList.isEmpty()) {
            if (nextTier != -1) {
                sendForensicLog("ORDER: Tier " + tier + " is empty. Skipping to next.");
                processTier(nextTier);
            }
            return;
        }

        Log.i(TAG, "Processing Tier " + tier + ": Sending " + targetList.size() + " items.");
        sendForensicLog("ORDER: Commencing Tier " + tier + " (" + targetList.size() + " items)");

        // Broadcast items in this tier with a short delay between each item
        for (int i = 0; i < targetList.size(); i++) {
            final JSONObject originalEvent = targetList.get(i);
            final boolean isLastInTier = (i == targetList.size() - 1);
            final int nextTierToRun = nextTier;

            // Delay each item by 500ms to prevent relay rate-limiting (spam detection)
            queueHandler.postDelayed(() -> {
                reSignAndSend(originalEvent);

                // If this was the last item in the tier, wait 3 seconds and start next tier.
                // This gap is critical for relays to index parent items before children arrive.
                if (isLastInTier && nextTierToRun != -1) {
                    sendForensicLog("ORDER: Tier " + tier + " complete. Waiting 3s for network indexing...");
                    queueHandler.postDelayed(() -> processTier(nextTierToRun), 3000);
                } else if (isLastInTier && nextTierToRun == -1) {
                    sendForensicLog("\n=== HEALING SEQUENCE COMPLETE ===");
                }
            }, i * 500L);
        }
    }

    /**
     * Logic: Generates a fresh BIP-340 signature with the CURRENT timestamp.
     * This is critical to force relays to overwrite their old pruned indexes.
     */
    private void reSignAndSend(JSONObject oldEvent) {
        try {
            JSONObject newEvent = new JSONObject();
            int kind = oldEvent.getInt("kind");
            String contentStr = oldEvent.getString("content");
            JSONObject content = new JSONObject(contentStr);

            // Identifies which metadata target is being healed for the console log
            String contextLabel = content.optString("category", content.optString("sub", content.optString("label", "Unknown")));
            Log.i(TAG, "HEAL_TRACE: Re-signing Kind " + kind + " for target '" + contextLabel + "'");
            sendForensicLog("HEAL: Re-signing " + kind + " for '" + contextLabel + "'");

            newEvent.put("kind", kind);
            newEvent.put("pubkey", db.getPublicKey());

            // =========================================================================
            // REFRESH NETWORK PERSISTENCE: OVERWRITE TIMESTAMP
            // This method uses current time to trick relays into resetting the pruning clock.
            // =========================================================================
            newEvent.put("created_at", System.currentTimeMillis() / 1000); 

            newEvent.put("content", contentStr);

            // TAG INTEGRITY: Carry over all indexing tags (t-tags, d-tags) for correct filtering
            newEvent.put("tags", oldEvent.getJSONArray("tags"));

            // IMPORTANT: Uses the re-signing logic that updates the Event ID and Signature
            JSONObject signed = NostrEventSigner.signHealedEvent(db.getPrivateKey(), newEvent);
            if (signed != null) {
                wsManager.broadcastEvent(signed.toString());
                Log.d(TAG, "Healed: " + signed.optString("id") + " (" + contextLabel + ")");
                sendForensicLog("CRYPTO: BIP-340 Schnorr Proof OK. Broadcasted.");
            } else {
                sendForensicLog("CRYPTO: FAILED to sign archived item.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Re-sign failure: " + e.getMessage());
            sendForensicLog("ERROR: Re-signing exception - " + e.getMessage());
        }
    }
}