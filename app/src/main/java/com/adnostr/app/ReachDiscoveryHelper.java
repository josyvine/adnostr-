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
 * UPDATED: Implements parallel multi-relay scanning to capture true global reach.
 * Aggregates unique pubkeys across the decentralized network for accurate counting.
 */
public class ReachDiscoveryHelper {

    private static final String TAG = "AdNostr_Discovery";

    // Strategic public relays for scanning global user activity
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
     * Scans multiple decentralized relays simultaneously to find users watching specific tags.
     */
    public static void discoverGlobalReach(List<String> hashtags, ReachCallback callback) {
        if (hashtags == null || hashtags.isEmpty()) {
            callback.onDiscoveryError("No hashtags provided");
            return;
        }

        new Thread(() -> {
            // Thread-safe set to store unique pubkeys found across all relays
            final Set<String> uniqueUserPubkeys = Collections.synchronizedSet(new HashSet<>());
            
            // Latch to wait for all relay scans to complete or timeout
            final CountDownLatch latch = new CountDownLatch(DISCOVERY_RELAYS.length);

            try {
                // 1. Construct the Search Filter
                JSONObject filter = new JSONObject();
                
                JSONArray kinds = new JSONArray();
                kinds.put(0);     // Metadata (Bio/Interests)
                kinds.put(10002); // Relay Lists (Standard interest location)
                kinds.put(30001); // Specific AdNostr interest broadcasts
                filter.put("kinds", kinds);

                JSONArray tags = new JSONArray();
                for (String h : hashtags) {
                    tags.put(h.toLowerCase());
                }
                filter.put("#t", tags);

                String subId = "reach-" + UUID.randomUUID().toString().substring(0, 4);
                String reqMessage = new JSONArray()
                        .put("REQ")
                        .put(subId)
                        .put(filter)
                        .toString();

                // 2. Launch parallel connections to all discovery relays
                for (String relayUrl : DISCOVERY_RELAYS) {
                    connectAndScan(relayUrl, reqMessage, uniqueUserPubkeys, latch);
                }

                // 3. Wait for network aggregation (Max 8 seconds)
                boolean finished = latch.await(8, TimeUnit.SECONDS);
                
                Log.i(TAG, "Parallel Discovery finished. Success: " + finished + ". Total unique users found: " + uniqueUserPubkeys.size());

                // 4. Return results to the Advertiser
                if (callback != null) {
                    callback.onReachCalculated(uniqueUserPubkeys.size());
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery thread orchestration failed: " + e.getMessage());
                callback.onDiscoveryError(e.getMessage());
            }
        }).start();
    }

    private static void connectAndScan(String relayUrl, String reqMessage, Set<String> results, CountDownLatch latch) {
        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "Scanning relay: " + relayUrl);
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
                            String pubkey = event.getString("pubkey");
                            results.add(pubkey); // Thread-safe set ignores duplicates
                        } else if ("EOSE".equals(type)) {
                            // Relay finished its search
                            close();
                        }
                    } catch (Exception e) {
                        // Silent error per relay
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    latch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    latch.countDown();
                }
            };
            
            client.setConnectionLostTimeout(10);
            client.connect();

        } catch (Exception e) {
            latch.countDown();
        }
    }
}