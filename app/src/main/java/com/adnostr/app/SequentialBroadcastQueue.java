package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
     */
    public void prepareArchive(String archiveJson) {
        try {
            JSONArray archive = new JSONArray(archiveJson);
            catTier.clear();
            fieldTier.clear();
            valueTier.clear();

            for (int i = 0; i < archive.length(); i++) {
                JSONObject event = archive.getJSONObject(i);
                int kind = event.getInt("kind");
                String contentStr = event.getString("content");
                JSONObject content = new JSONObject(contentStr);

                if (kind == 30006) {
                    if ("category".equals(content.optString("type"))) {
                        catTier.add(event);
                    } else if ("field".equals(content.optString("type"))) {
                        fieldTier.add(event);
                    }
                } else if (kind == 30007) {
                    valueTier.add(event);
                }
            }
            
            Log.d(TAG, "Queue Prepared: Tier1=" + catTier.size() + ", Tier2=" + fieldTier.size() + ", Tier3=" + valueTier.size());
            sendForensicLog("SUCCESS: Archive sorted into " + (catTier.size() + fieldTier.size() + valueTier.size()) + " restoration frames.");

        } catch (Exception e) {
            Log.e(TAG, "Queue preparation failed: " + e.getMessage());
            sendForensicLog("ERROR: Archive sorting failed - " + e.getMessage());
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
            if (nextTier != -1) processTier(nextTier);
            return;
        }

        Log.i(TAG, "Processing Tier " + tier + ": Sending " + targetList.size() + " items.");
        sendForensicLog("ORDER: Commencing Tier " + tier + " (" + targetList.size() + " items)");

        // Broadcast items in this tier with a short delay between each item
        for (int i = 0; i < targetList.size(); i++) {
            final JSONObject originalEvent = targetList.get(i);
            final int index = i;
            final boolean isLastInTier = (i == targetList.size() - 1);

            // Delay each item by 500ms to prevent relay rate-limiting
            queueHandler.postDelayed(() -> {
                reSignAndSend(originalEvent);
                
                // If this was the last item in the tier, wait 3 seconds and start next tier
                if (isLastInTier && nextTier != -1) {
                    sendForensicLog("ORDER: Tier " + tier + " complete. Transitioning...");
                    queueHandler.postDelayed(() -> processTier(nextTier), 3000);
                } else if (isLastInTier && nextTier == -1) {
                    sendForensicLog("\n=== HEALING SEQUENCE COMPLETE ===");
                }
            }, i * 500L);
        }
    }

    /**
     * Logic: Generates a fresh BIP-340 signature with the CURRENT timestamp.
     * This is critical to force relays to overwrite their old pruned indexes.
     * DROPDOWN FIX: Ensures context metadata is explicitly logged during re-signing.
     */
    private void reSignAndSend(JSONObject oldEvent) {
        try {
            JSONObject newEvent = new JSONObject();
            int kind = oldEvent.getInt("kind");
            String contentStr = oldEvent.getString("content");
            JSONObject content = new JSONObject(contentStr);

            // DROPDOWN DROPOUT TRACE: Identify which category is being healed
            String contextLabel = content.optString("category", content.optString("sub", "Unknown"));
            Log.i(TAG, "HEAL_TRACE: Re-signing Kind " + kind + " for target '" + contextLabel + "'");
            sendForensicLog("HEAL: Re-signing " + kind + " for '" + contextLabel + "'");

            newEvent.put("kind", kind);
            newEvent.put("pubkey", db.getPublicKey());
            newEvent.put("created_at", System.currentTimeMillis() / 1000); // RESET PRUNING CLOCK
            newEvent.put("content", contentStr);
            
            // TAG INTEGRITY: Carry over all indexing tags (t-tags, d-tags) for correct filtering
            newEvent.put("tags", oldEvent.getJSONArray("tags"));

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), newEvent);
            if (signed != null) {
                wsManager.broadcastEvent(signed.toString());
                Log.d(TAG, "Sequentially broadcasted archived item: " + signed.optString("id") + " (Context: " + contextLabel + ")");
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