package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Discovery Engine for AdNostr Advertisers.
 * UPDATED: Optimized to search specifically for Kind 30001 (User Interest) events.
 * This ensures that the hashtag search returns real user counts based on published metadata.
 * FIXED: Implements Pool Alignment, Tag Sanitization, and Increased Parallelism.
 */
public class ReachDiscoveryHelper {

    private static final String TAG = "AdNostr_Discovery";

    public interface ReachCallback {
        void onReachCalculated(int totalUsers);
        void onDiscoveryError(String error);
    }

    /**
     * Scans the network for unique users who have published Kind 30001 
     * events matching the targeted hashtags.
     * 
     * @param context Required to fetch the full 31+ relay pool from Database.
     * @param hashtags List of hashtags provided by the advertiser.
     * @param callback Interface to return the final count to the UI.
     */
    public static void discoverGlobalReach(Context context, List<String> hashtags, ReachCallback callback) {
        if (hashtags == null || hashtags.isEmpty()) {
            if (callback != null) callback.onDiscoveryError("No hashtags provided");
            return;
        }

        // 1. POOL ALIGNMENT: Fetch the full pool from the database helper
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        Set<String> relayPool = db.getRelayPool();

        new Thread(() -> {
            // Thread-safe set to store unique pubkeys found across the pool
            final Set<String> uniqueUserPubkeys = Collections.synchronizedSet(new HashSet<>());

            // 3. INCREASED PARALLELISM: Scale latch to wait for the entire relay pool
            final CountDownLatch latch = new CountDownLatch(relayPool.size());

            try {
                // Construct the Nostr Search Filter
                JSONObject filter = new JSONObject();

                JSONArray kinds = new JSONArray();
                kinds.put(30001); // User Interest List
                filter.put("kinds", kinds);

                JSONArray tags = new JSONArray();
                for (String h : hashtags) {
                    // 2. TAG SANITIZATION: Strip '#' to ensure search matches clean protocol tags
                    tags.put(h.toLowerCase().replace("#", ""));
                }
                filter.put("#t", tags); 

                String subId = "reach-" + UUID.randomUUID().toString().substring(0, 4);
                String reqMessage = new JSONArray()
                        .put("REQ")
                        .put(subId)
                        .put(filter)
                        .toString();

                // Launch parallel scans across the entire database relay pool
                for (String relayUrl : relayPool) {
                    connectAndScanRelay(relayUrl, reqMessage, uniqueUserPubkeys, latch);
                }

                // Wait for the network search to aggregate (8 seconds max)
                boolean finished = latch.await(8, TimeUnit.SECONDS);

                Log.i(TAG, "Discovery Aggregate finished. Success: " + finished 
                        + ". Unique users found: " + uniqueUserPubkeys.size());

                // 4. Return results to the Advertiser UI
                if (callback != null) {
                    callback.onReachCalculated(uniqueUserPubkeys.size());
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery orchestration failed: " + e.getMessage());
                if (callback != null) callback.onDiscoveryError(e.getMessage());
            }
        }).start();
    }

    /**
     * Internal worker to connect to a single relay and scrape matched events.
     */
    private static void connectAndScanRelay(String relayUrl, String reqMessage, Set<String> results, CountDownLatch latch) {
        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "Relay Scan Initiated: " + relayUrl);
                    send(reqMessage);
                }

                @Override
                public void onMessage(String message) {
                    try {
                        if (!message.startsWith("[")) return;

                        JSONArray resp = new JSONArray(message);
                        String type = resp.getString(0);

                        if ("EVENT".equals(type)) {
                            JSONObject event = resp.getJSONObject(2);
                            // Aggregate the unique identity (Pubkey)
                            String pubkey = event.getString("pubkey");
                            results.add(pubkey); 

                        } else if ("EOSE".equals(type)) {
                            // Relay has finished scanning its database
                            close();
                        }
                    } catch (Exception e) {
                        // Ignore individual event parsing errors
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    latch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Scan Error [" + relayUrl + "]: " + ex.getMessage());
                    latch.countDown();
                }
            };

            client.setConnectionLostTimeout(10);
            client.connect();

        } catch (Exception e) {
            Log.e(TAG, "WebSocket initialization failed for " + relayUrl);
            latch.countDown();
        }
    }
}