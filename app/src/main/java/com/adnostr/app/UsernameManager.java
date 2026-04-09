package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decentralized Username Manager.
 * Handles querying the Nostr network to ensure username uniqueness,
 * as well as claiming and releasing usernames using Kind 0 (Metadata) events.
 */
public class UsernameManager {

    private static final String TAG = "AdNostr_UserManager";
    
    // We use nostr.band as the bootstrap for checking because it supports NIP-50 search queries
    private static final String SEARCH_RELAY = "wss://relay.nostr.band";

    /**
     * Interface to pass results back to the UI thread.
     */
    public interface UsernameCallback {
        void onResult(boolean isAvailable, String message);
    }

    /**
     * Interface for claim/release broadcast results.
     */
    public interface PublishCallback {
        void onComplete(boolean success, String message);
    }

    /**
     * Checks the Nostr network to see if a username is already taken by another PubKey.
     * 
     * @param username The desired username to check.
     * @param myPubkey The current user's public key (to ensure we don't block ourselves).
     * @param callback Returns true if available, false if taken.
     */
    public static void checkAvailability(final String username, final String myPubkey, final UsernameCallback callback) {
        if (username == null || username.trim().isEmpty()) {
            callback.onResult(false, "Username cannot be empty.");
            return;
        }

        final String targetName = username.trim().toLowerCase();
        final AtomicBoolean isTaken = new AtomicBoolean(false);
        final AtomicBoolean hasResponded = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                WebSocketClient client = new WebSocketClient(new URI(SEARCH_RELAY)) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        try {
                            // Construct NIP-50 Search Query for Kind 0 Metadata
                            JSONObject filter = new JSONObject();
                            filter.put("kinds", new JSONArray().put(0));
                            filter.put("search", targetName);

                            String subId = "namecheck-" + UUID.randomUUID().toString().substring(0, 6);
                            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();
                            
                            send(req);
                            Log.d(TAG, "Sent username availability check: " + targetName);
                        } catch (Exception e) {
                            Log.e(TAG, "Error building search query: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onMessage(String message) {
                        try {
                            if (!message.startsWith("[")) return;
                            JSONArray msgArray = new JSONArray(message);
                            String type = msgArray.getString(0);

                            if ("EVENT".equals(type)) {
                                JSONObject event = msgArray.getJSONObject(2);
                                String eventPubkey = event.getString("pubkey");
                                
                                // Parse the metadata content
                                String contentStr = event.optString("content", "{}");
                                JSONObject content = new JSONObject(contentStr);
                                String foundName = content.optString("name", "").trim().toLowerCase();

                                // If the exact name is found AND it belongs to a DIFFERENT user
                                if (foundName.equals(targetName) && !eventPubkey.equals(myPubkey)) {
                                    if (hasResponded.compareAndSet(false, true)) {
                                        isTaken.set(true);
                                        close();
                                        new Handler(Looper.getMainLooper()).post(() -> 
                                            callback.onResult(false, "Username is already taken by another user.")
                                        );
                                    }
                                }
                            } else if ("EOSE".equals(type)) {
                                // End of Stored Events - If we get here and it's not taken, it's available!
                                if (hasResponded.compareAndSet(false, true)) {
                                    close();
                                    new Handler(Looper.getMainLooper()).post(() -> 
                                        callback.onResult(true, "Username is available!")
                                    );
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing search result: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        // Fallback if closed before EOSE
                        if (hasResponded.compareAndSet(false, true)) {
                            new Handler(Looper.getMainLooper()).post(() -> 
                                callback.onResult(!isTaken.get(), isTaken.get() ? "Username taken." : "Username appears available.")
                            );
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "Search Relay Error: " + ex.getMessage());
                        if (hasResponded.compareAndSet(false, true)) {
                            new Handler(Looper.getMainLooper()).post(() -> 
                                callback.onResult(false, "Network error while checking availability.")
                            );
                        }
                    }
                };

                client.setConnectionLostTimeout(10);
                client.connect();

                // 5-Second Timeout Safety Net
                Thread.sleep(5000);
                if (hasResponded.compareAndSet(false, true)) {
                    client.close();
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onResult(!isTaken.get(), isTaken.get() ? "Username taken." : "Search timed out, assuming available.")
                    );
                }

            } catch (Exception e) {
                if (hasResponded.compareAndSet(false, true)) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onResult(false, "System error during network check.")
                    );
                }
            }
        }).start();
    }

    /**
     * Claims a username by broadcasting a Kind 0 (Metadata) event to the network.
     * 
     * @param context Used to fetch relay pool.
     * @param username The verified available username.
     * @param myPrivkey User's private key for signing.
     * @param myPubkey User's public key.
     * @param callback UI callback.
     */
    public static void claimUsername(Context context, String username, String myPrivkey, String myPubkey, PublishCallback callback) {
        broadcastKind0(context, username, myPrivkey, myPubkey, callback);
    }

    /**
     * Releases a username by broadcasting an empty Kind 0 event, overwriting the old one.
     * 
     * @param context Used to fetch relay pool.
     * @param myPrivkey User's private key.
     * @param myPubkey User's public key.
     * @param callback UI callback.
     */
    public static void releaseUsername(Context context, String myPrivkey, String myPubkey, PublishCallback callback) {
        broadcastKind0(context, "", myPrivkey, myPubkey, callback);
    }

    /**
     * Internal helper to construct and broadcast the Kind 0 event.
     */
    private static void broadcastKind0(Context context, String nameValue, String privkey, String pubkey, PublishCallback callback) {
        try {
            JSONObject content = new JSONObject();
            content.put("name", nameValue.trim());
            content.put("about", "AdNostr Verified Identity");

            JSONObject event = new JSONObject();
            event.put("kind", 0);
            event.put("pubkey", pubkey);
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", content.toString());
            event.put("tags", new JSONArray());

            JSONObject signedEvent = NostrEventSigner.signEvent(privkey, event);

            if (signedEvent != null) {
                Set<String> relayPool = AdNostrDatabaseHelper.getInstance(context).getRelayPool();
                
                NostrPublisher.publishToPool(relayPool, signedEvent, (relayUrl, success, message) -> {
                    // We don't need UI logs for this background process, NostrPublisher handles it
                });

                new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onComplete(true, nameValue.isEmpty() ? "Username released." : "Username successfully claimed!")
                );
            } else {
                new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onComplete(false, "Cryptographic signing failed.")
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Error building Kind 0 event: " + e.getMessage());
            new Handler(Looper.getMainLooper()).post(() -> 
                callback.onComplete(false, "Event creation failed: " + e.getMessage())
            );
        }
    }
}