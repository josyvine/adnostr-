package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
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
 * UPDATED: Added Relay NOTICE and CLOSED handling to debug "0 users found" issues.
 * FIXED: Extracts usernames from the content payload to identify users in the search results.
 * FIXED: Support for both JSON and Plain-Text usernames to ensure discovery visibility.
 * ENHANCEMENT: Integrated Hybrid Hashtag Registry (Cases 1, 2, 3) to protect private reach data.
 * ENHANCEMENT: Master App-Level Decryption implemented to read secure User Interest lists.
 * ENHANCEMENT: Discovery logging respects Global Console Visibility and Debug/Professional modes.
 */
public class ReachDiscoveryHelper {

    private static final String TAG = "AdNostr_Discovery";

    // UPDATED Interface: Now returns a list of usernames along with the count
    public interface ReachCallback {
        void onReachCalculated(int totalUsers, List<String> usernames);
        void onDiscoveryError(String error);
    }

    /**
     * Scans the network for unique users who have published Kind 30001 
     * events matching the targeted hashtags.
     * 
     * @param context Required to fetch the full 31+ relay pool from Database.
     * @param hashtags List of hashtags provided by the advertiser.
     * @param callback Interface to return the final count and names to the UI.
     */
    public static void discoverGlobalReach(Context context, List<String> hashtags, ReachCallback callback) {
        if (hashtags == null || hashtags.isEmpty()) {
            if (callback != null) callback.onDiscoveryError("No hashtags provided");
            return;
        }

        // =========================================================================
        // FEATURE 1: HYBRID HASHTAG REGISTRY (PERMISSION LOGIC)
        // Before calculating reach, background-scan for ownership events.
        // =========================================================================
        String primaryTag = hashtags.get(0).toLowerCase().replace("#", "");
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

        HashtagRegistryManager.checkOwnership(context, primaryTag, db.getPublicKey(), new HashtagRegistryManager.OwnershipCallback() {
            @Override
            public void onResult(int status, String ownerPubkey) {
                // CASE 3: OWNED BY ANOTHER
                if (status == HashtagRegistryManager.STATUS_TAKEN) {
                    if (callback != null) {
                        callback.onDiscoveryError("DECLINED: Tag Owned by " + ownerPubkey);
                    }
                    return;
                }

                // CASE 1 & 2: PUBLIC or OWNED BY YOU - Proceed with Scan
                executeNetworkDiscovery(context, hashtags, callback);
            }
        });
    }

    /**
     * Internal logic to execute the parallel relay scan after permission is granted.
     */
    private static void executeNetworkDiscovery(Context context, List<String> hashtags, ReachCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        Set<String> relayPool = db.getRelayPool();

        new Thread(() -> {
            // Thread-safe set to store unique pubkeys found across the pool
            final Set<String> uniqueUserPubkeys = Collections.synchronizedSet(new HashSet<>());

            // NEW: Thread-safe set to store discovered usernames
            final Set<String> discoveredUsernames = Collections.synchronizedSet(new HashSet<>());

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
                    connectAndScanRelay(context, relayUrl, reqMessage, uniqueUserPubkeys, discoveredUsernames, latch);
                }

                // Wait for the network search to aggregate (12 seconds max - increased for slow relays)
                boolean finished = latch.await(12, TimeUnit.SECONDS);

                // ENHANCEMENT: Filter detailed internal discovery logs
                if (db.isConsoleLogEnabled()) {
                    if (db.isDebugModeActive()) {
                        Log.i(TAG, "Discovery Aggregate finished. Success: " + finished 
                                + ". Unique users found: " + uniqueUserPubkeys.size());
                    } else {
                        Log.i(TAG, "Network reach calculation complete. Found " + uniqueUserPubkeys.size() + " active profiles.");
                    }
                }

                // 4. Return results (Count + List of Names) to the Advertiser UI
                if (callback != null) {
                    callback.onReachCalculated(uniqueUserPubkeys.size(), new ArrayList<>(discoveredUsernames));
                }

            } catch (Exception e) {
                Log.e(TAG, "Discovery orchestration failed: " + e.getMessage());
                if (callback != null) callback.onDiscoveryError(e.getMessage());
            }
        }).start();
    }

    /**
     * Internal worker to connect to a single relay and scrape matched events.
     * UPDATED: Now parses NOTICE and CLOSED messages to identify signature rejection.
     * FIXED: Parses the content field for optional 'username' metadata.
     * ENHANCEMENT: Master App-Level Decryption to unlock usernames from interest events.
     * ENHANCEMENT: Visibility logic applied to relay session tracking.
     */
    private static void connectAndScanRelay(Context context, String relayUrl, String reqMessage, Set<String> results, Set<String> usernames, CountDownLatch latch) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        
        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    if (db.isConsoleLogEnabled()) {
                        if (db.isDebugModeActive()) {
                            Log.d(TAG, "Relay Scan Initiated: " + relayUrl);
                        } else {
                            Log.d(TAG, "Relay Discovery: Initiating connection to " + relayUrl);
                        }
                    }
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

                            // =========================================================================
                            // ENHANCEMENT: MASTER APP-LEVEL DECRYPTION
                            // =========================================================================
                            String contentRaw = event.optString("content", "").trim();
                            if (!contentRaw.isEmpty()) {
                                try {
                                    // Attempt to unlock the payload using the hardcoded App Master Key
                                    String decryptedContent = EncryptionUtils.decryptPayload(contentRaw);
                                    
                                    if (decryptedContent.startsWith("{")) {
                                        // Handle JSON format username
                                        JSONObject contentJson = new JSONObject(decryptedContent);
                                        String foundName = contentJson.optString("username", "");
                                        if (!foundName.isEmpty()) {
                                            usernames.add(foundName);
                                        }
                                    } else {
                                        // Handle Plain-Text format username
                                        if (!decryptedContent.contains("\"title\"")) {
                                            usernames.add(decryptedContent);
                                        }
                                    }
                                } catch (Exception e) {
                                    // Fallback to legacy/unencrypted parsing (if applicable)
                                    if (contentRaw.startsWith("{")) {
                                        try {
                                            JSONObject contentJson = new JSONObject(contentRaw);
                                            String foundName = contentJson.optString("username", "");
                                            if (!foundName.isEmpty()) usernames.add(foundName);
                                        } catch (Exception ignored) {}
                                    } else if (!contentRaw.contains("\"") && contentRaw.length() < 30) {
                                        usernames.add(contentRaw);
                                    }
                                }
                            }

                        } else if ("EOSE".equals(type)) {
                            // Relay has finished scanning its database
                            close();
                        } else if ("NOTICE".equals(type)) {
                            // Relay is sending a warning (e.g., restricted access or invalid filters)
                            String note = resp.optString(1, "");
                            
                            if (db.isConsoleLogEnabled()) {
                                Log.w(TAG, "Relay NOTICE [" + relayUrl + "]: " + note);
                            }

                            if (note.toLowerCase().contains("invalid") || note.toLowerCase().contains("signature")) {
                                Log.e(TAG, "CRITICAL: Relay reporting signature issues during search.");
                            }
                        } else if ("CLOSED".equals(type)) {
                            // Relay forcefully closed the subscription
                            String reason = resp.optString(2, "No reason");
                            
                            if (db.isConsoleLogEnabled()) {
                                Log.w(TAG, "Relay CLOSED sub [" + relayUrl + "]: " + reason);
                            }
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
                    if (db.isConsoleLogEnabled()) {
                        Log.e(TAG, "Scan Error [" + relayUrl + "]: " + ex.getMessage());
                    }
                    latch.countDown();
                }
            };

            // Increased timeout to give slower global relays a chance to respond
            client.setConnectionLostTimeout(15);
            client.connect();

        } catch (Exception e) {
            Log.e(TAG, "WebSocket initialization failed for " + relayUrl);
            latch.countDown();
        }
    }
}