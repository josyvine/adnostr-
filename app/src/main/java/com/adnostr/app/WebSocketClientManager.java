package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decentralized Network Manager.
 * UPDATED: Implements dynamic hashtag subscriptions and Kind 1 ad filtering 
 * to ensure users receive relevant ads from the network.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;

    // Thread-safe map of active relay connections (URL -> Client)
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();

    // Callback to notify UI components of network changes
    private RelayStatusListener statusListener;

    public interface RelayStatusListener {
        void onRelayConnected(String url);
        void onRelayDisconnected(String url, String reason);
        void onMessageReceived(String url, String message);
        void onError(String url, Exception ex);
    }

    private WebSocketClientManager() {
        // Private constructor for Singleton
    }

    public static synchronized WebSocketClientManager getInstance() {
        if (instance == null) {
            instance = new WebSocketClientManager();
        }
        return instance;
    }

    public void setStatusListener(RelayStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Connects to a set of relays simultaneously.
     */
    public void connectPool(Set<String> relayUrls) {
        if (relayUrls == null || relayUrls.isEmpty()) return;

        Log.i(TAG, "Initiating connection to " + relayUrls.size() + " decentralized nodes...");
        for (String url : relayUrls) {
            connectRelay(url);
        }
    }

    /**
     * Attempts to connect to a decentralized relay if not already active.
     */
    public void connectRelay(final String relayUrl) {
        if (relayUrl == null || !relayUrl.startsWith("wss://")) return;

        if (activeRelays.containsKey(relayUrl)) {
            WebSocketClient existing = activeRelays.get(relayUrl);
            if (existing != null && !existing.isClosed()) {
                return;
            }
        }

        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "Relay Connection Established: " + relayUrl);
                    activeRelays.put(relayUrl, this);
                    
                    // NEW: Automatically subscribe to matching ads upon connection
                    subscribeToUserInterests(this);

                    if (statusListener != null) {
                        statusListener.onRelayConnected(relayUrl);
                    }
                }

                @Override
                public void onMessage(String message) {
                    if (statusListener != null) {
                        statusListener.onMessageReceived(relayUrl, message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Relay Closed [" + relayUrl + "]: " + reason);
                    activeRelays.remove(relayUrl);
                    if (statusListener != null) {
                        statusListener.onRelayDisconnected(relayUrl, reason);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Relay Failure [" + relayUrl + "]: " + ex.getMessage());
                    activeRelays.remove(relayUrl);
                    if (statusListener != null) {
                        statusListener.onError(relayUrl, ex);
                    }
                }
            };

            client.setConnectionLostTimeout(30); 
            client.connect();

        } catch (Exception e) {
            Log.e(TAG, "Initial WebSocket setup failed for " + relayUrl + ": " + e.getMessage());
        }
    }

    /**
     * NEW: Generates a Nostr REQ (Subscription) based on the user's saved hashtags.
     * This makes the relay push matching ads to the device.
     */
    private void subscribeToUserInterests(WebSocketClient client) {
        try {
            // Context is required to get database instance
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(null);
            Set<String> interests = db.getInterests();

            if (interests.isEmpty()) return;

            // 1. Build Tag Array (removing # for protocol match)
            JSONArray tagArray = new JSONArray();
            for (String tag : interests) {
                tagArray.put(tag.toLowerCase().replace("#", ""));
            }

            // 2. Create Nostr Filter
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(1)); // Listen for Ad Broadcasts (Kind 1)
            filter.put("#t", tagArray); // Filter by the user's hashtags

            // 3. Wrap in standard REQ format
            JSONArray req = new JSONArray();
            req.put("REQ");
            req.put("ads_subscription_01");
            req.put(filter);

            client.send(req.toString());
            Log.d(TAG, "Dynamic subscription sent: " + req.toString());

        } catch (Exception e) {
            Log.e(TAG, "Failed to create dynamic subscription: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a signed Nostr event JSON to all active relays in the pool.
     */
    public void broadcastEvent(String eventJson) {
        if (activeRelays.isEmpty()) {
            Log.w(TAG, "Zero active relays. Broadcast cancelled.");
            return;
        }

        String nostrMessage = "[\"EVENT\"," + eventJson + "]";

        int sentCount = 0;
        for (Map.Entry<String, WebSocketClient> entry : activeRelays.entrySet()) {
            WebSocketClient client = entry.getValue();
            if (client != null && client.isOpen()) {
                client.send(nostrMessage);
                sentCount++;
            }
        }
        Log.i(TAG, "Event broadcasted to " + sentCount + " active nodes.");
    }

    public void subscribe(String relayUrl, String subscriptionJson) {
        WebSocketClient client = activeRelays.get(relayUrl);
        if (client != null && client.isOpen()) {
            client.send(subscriptionJson);
        }
    }

    public void disconnectRelay(String relayUrl) {
        WebSocketClient client = activeRelays.remove(relayUrl);
        if (client != null) {
            client.close();
        }
    }

    public void shutdown() {
        for (String url : activeRelays.keySet()) {
            disconnectRelay(url);
        }
        activeRelays.clear();
        Log.i(TAG, "WebSocket Management Service Stopped.");
    }

    public int getConnectedRelayCount() {
        int count = 0;
        for (WebSocketClient client : activeRelays.values()) {
            if (client != null && client.isOpen()) {
                count++;
            }
        }
        return count;
    }
}