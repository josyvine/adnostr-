package com.adnostr.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Discovery Engine for AdNostr Advertisers.
 * UPDATED: Replaced simulated random reach with real-time decentralized 
 * peer counting logic. Identifies unique pubkeys watching specific hashtags.
 */
public class ReachDiscoveryHelper {

    private static final String TAG = "AdNostr_Discovery";
    
    // Major public relays to scan for global user interest metadata
    private static final String[] DISCOVERY_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band"
    };

    public interface ReachCallback {
        void onReachCalculated(int totalUsers);
        void onDiscoveryError(String error);
    }

    /**
     * Scans the network for unique users associated with specific hashtags.
     */
    public static void discoverGlobalReach(List<String> hashtags, ReachCallback callback) {
        if (hashtags == null || hashtags.isEmpty()) {
            callback.onDiscoveryError("No hashtags provided");
            return;
        }

        new Thread(() -> {
            // Set to store unique public keys found to ensure accurate reach counting
            final Set<String> uniqueUserPubkeys = new HashSet<>();
            
            try {
                // Construct the Nostr Subscription Filter
                // We search for users who have tagged their relay lists or ads with these hashtags
                JSONObject filter = new JSONObject();
                
                // kind 10002 = Relay List Metadata (where users list interests)
                // kind 30001 = Ad Metadata / Followed Hashtags in AdNostr
                JSONArray kinds = new JSONArray();
                kinds.put(10002);
                kinds.put(30001);
                filter.put("kinds", kinds);
                
                JSONArray tags = new JSONArray();
                for (String h : hashtags) {
                    tags.put(h.toLowerCase());
                }
                filter.put("#t", tags);

                String subId = "discovery-" + UUID.randomUUID().toString().substring(0, 6);
                String reqMessage = new JSONArray()
                        .put("REQ")
                        .put(subId)
                        .put(filter)
                        .toString();

                // Connect to a primary bootstrap relay for the scan
                WebSocketClient client = new WebSocketClient(new URI(DISCOVERY_RELAYS[0])) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        Log.d(TAG, "Discovery scan started on: " + DISCOVERY_RELAYS[0]);
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
                                // Add the pubkey to the Set. 
                                // Duplicate pubkeys are automatically ignored by the Set.
                                String pubkey = event.getString("pubkey");
                                uniqueUserPubkeys.add(pubkey);
                                Log.v(TAG, "User found for tag: " + pubkey);

                            } else if ("EOSE".equals(type)) {
                                // End Of Stored Events - Relay has finished sending historical matches
                                Log.i(TAG, "Relay scan finished. Unique users: " + uniqueUserPubkeys.size());
                                finalizeCount(uniqueUserPubkeys.size(), callback, this);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing discovery message: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        Log.d(TAG, "Discovery connection closed.");
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "Discovery error: " + ex.getMessage());
                        callback.onDiscoveryError(ex.getMessage());
                    }
                };

                client.connect();

                // Safety Timeout: 6 seconds. 
                // If EOSE is not received, return the current count accumulated.
                Thread.sleep(6000); 
                if (client.isOpen()) {
                    finalizeCount(uniqueUserPubkeys.size(), callback, client);
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery thread failed: " + e.getMessage());
                callback.onDiscoveryError(e.getMessage());
            }
        }).start();
    }

    /**
     * Closes the connection and returns the real count to the UI.
     */
    private static void finalizeCount(int count, ReachCallback callback, WebSocketClient client) {
        if (client != null && client.isOpen()) {
            client.close();
        }
        
        if (callback != null) {
            // FIXED: Returns the REAL count. No more random numbers.
            callback.onReachCalculated(count);
        }
    }
}