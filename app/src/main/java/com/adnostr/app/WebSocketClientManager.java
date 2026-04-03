package com.adnostr.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decentralized Network Manager.
 * UPDATED: Added support for connecting to a massive relay pool simultaneously 
 * to restore the 30+ relay connection status.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;

    // Thread-safe map of active relay connections (URL -> Client)
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();

    // Callback to notify UI components of network changes
    private RelayStatusListener statusListener;

    /**
     * Interface for monitoring relay connectivity status.
     * Note: Callbacks occur on the WebSocket background thread.
     */
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
     * NEW: Connects to a set of relays simultaneously.
     * Restores the decentralized reach by utilizing the full bootstrap pool.
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
        // Validation: Ensure URL is not empty and starts with wss://
        if (relayUrl == null || !relayUrl.startsWith("wss://")) return;

        if (activeRelays.containsKey(relayUrl)) {
            WebSocketClient existing = activeRelays.get(relayUrl);

            // FIXED LOGIC: If the socket exists and is NOT closed, skip to avoid duplicates.
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

            // Set a reasonable connection timeout for decentralized nodes
            client.setConnectionLostTimeout(30); 
            client.connect();

        } catch (Exception e) {
            Log.e(TAG, "Initial WebSocket setup failed for " + relayUrl + ": " + e.getMessage());
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

    /**
     * Subscribes to specific event filters on all active relays.
     */
    public void subscribeAll(String subscriptionJson) {
        for (WebSocketClient client : activeRelays.values()) {
            if (client != null && client.isOpen()) {
                client.send(subscriptionJson);
            }
        }
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