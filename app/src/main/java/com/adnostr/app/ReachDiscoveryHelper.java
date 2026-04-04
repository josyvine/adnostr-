package com.adnostr.app;

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
 */
public class ReachDiscoveryHelper {

    private static final String TAG = "AdNostr_Discovery";

    // Global relays used to aggregate user reach data
    private static final String[] DISCOVERY_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://relay.primal.net"
    };

    public interface ReachCallback {
        void onReachCalculated(int totalUsers);
        void onDiscoveryError(String error);
    }

    /**
     * Scans the network for unique users who have published Kind 30001 
     * events matching the targeted hashtags.
     * 
     * @param hashtags List of hashtags provided by the advertiser.
     * @param callback Interface to return the final count to the UI.
     */
    public static void discoverGlobalReach(List<String> hashtags, ReachCallback callback) {
        if (hashtags == null || hashtags.isEmpty()) {
            callback.onDiscoveryError("No hashtags provided");
            return;
        }

        new Thread(() -> {
            // Thread-safe set to store unique pubkeys found across the pool
            final Set<String> uniqueUserPubkeys = Collections.synchronizedSet(new HashSet<>());
            
            // Wait for all relay connections to finish their task or timeout
            final CountDownLatch latch = new CountDownLatch(DISCOVERY_RELAYS.length);

            try {
                // 1. Construct the Nostr Search Filter
                // We specifically look for Kind 30001 where users list their watched hashtags
                JSONObject filter = new JSONObject();
                
                JSONArray kinds = new JSONArray();
                kinds.put(30001); // User Interest List
                filter.put("kinds", kinds);

                JSONArray tags = new JSONArray();
                for (String h : hashtags) {
                    tags.put(h.toLowerCase());
                }
                filter.put("#t", tags); // Filter relays by hashtag tags

                String subId = "reach-" + UUID.randomUUID().toString().substring(0, 4);
                String reqMessage = new JSONArray()
                        .put("REQ")
                        .put(subId)
                        .put(filter)
                        .toString();

                // 2. Query all Discovery Relays in parallel
                for (String relayUrl : DISCOVERY_RELAYS) {
                    connectAndScanRelay(relayUrl, reqMessage, uniqueUserPubkeys, latch);
                }

                // 3. Wait for the network search to aggregate (8 seconds max)
                boolean finished = latch.await(8, TimeUnit.SECONDS);
                
                Log.i(TAG, "Discovery Aggregate finished. Success: " + finished 
                        + ". Unique users found: " + uniqueUserPubkeys.size());

                // 4. Return results to the Advertiser UI
                if (callback != null) {
                    // This returns the REAL count of unique pubkeys found on the network
                    callback.onReachCalculated(uniqueUserPubkeys.size());
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery orchestration failed: " + e.getMessage());
                callback.onDiscoveryError(e.getMessage());
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