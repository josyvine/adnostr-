package com.adnostr.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decentralized Network Manager.
 * Maintains persistent WebSocket connections to multiple Nostr relays.
 * Handles event broadcasting (Ads) and real-time subscription management.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;

    // Thread-safe map of active relay connections
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();
    
    // Callback to notify UI components of network changes
    private RelayStatusListener statusListener;

    /**
     * Interface for monitoring relay connectivity status.
     */
    public interface RelayStatusListener {
        void onRelayConnected(String url);
        void onRelayDisconnected(String url, String reason);
        void onMessageReceived(String url, String message);
        void onError(String url, Exception ex);
    }

    private WebSocketClientManager() {
        // Private constructor for Singleton pattern
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
     * Attempts to connect to a new decentralized relay.
     * 
     * @param relayUrl The wss:// address of the relay.
     */
    public void connectRelay(final String relayUrl) {
        if (activeRelays.containsKey(relayUrl)) {
            Log.d(TAG, "Relay already connected or connecting: " + relayUrl);
            return;
        }

        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "Successfully connected to relay: " + relayUrl);
                    activeRelays.put(relayUrl, this);
                    if (statusListener != null) {
                        statusListener.onRelayConnected(relayUrl);
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.v(TAG, "Message from " + relayUrl + ": " + message);
                    if (statusListener != null) {
                        statusListener.onMessageReceived(relayUrl, message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Disconnected from " + relayUrl + ". Reason: " + reason);
                    activeRelays.remove(relayUrl);
                    if (statusListener != null) {
                        statusListener.onRelayDisconnected(relayUrl, reason);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Relay Error [" + relayUrl + "]: " + ex.getMessage());
                    if (statusListener != null) {
                        statusListener.onError(relayUrl, ex);
                    }
                }
            };

            Log.d(TAG, "Initiating connection to " + relayUrl);
            client.connect();

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize WebSocket for " + relayUrl + ": " + e.getMessage());
            // Global Exception Handler in AdNostrApplication will catch fatal setup errors
        }
    }

    /**
     * Broadcasts a signed Nostr event (like an Ad or Profile) to all active relays.
     * 
     * @param eventJson The signed JSON event string.
     */
    public void broadcastEvent(String eventJson) {
        if (activeRelays.isEmpty()) {
            Log.w(TAG, "Broadcast failed: No active relay connections.");
            return;
        }

        String nostrMessage = "[\"EVENT\"," + eventJson + "]";
        
        for (Map.Entry<String, WebSocketClient> entry : activeRelays.entrySet()) {
            WebSocketClient client = entry.getValue();
            if (client.isOpen()) {
                client.send(nostrMessage);
                Log.d(TAG, "Event sent to: " + entry.getKey());
            }
        }
    }

    /**
     * Sends a subscription request (REQ) to a specific relay.
     * 
     * @param relayUrl The target relay.
     * @param subscriptionJson The REQ JSON message.
     */
    public void subscribe(String relayUrl, String subscriptionJson) {
        WebSocketClient client = activeRelays.get(relayUrl);
        if (client != null && client.isOpen()) {
            client.send(subscriptionJson);
            Log.d(TAG, "Subscription sent to " + relayUrl);
        }
    }

    /**
     * Closes a specific relay connection.
     */
    public void disconnectRelay(String relayUrl) {
        WebSocketClient client = activeRelays.remove(relayUrl);
        if (client != null) {
            client.close();
        }
    }

    /**
     * Closes all network connections.
     */
    public void shutdown() {
        for (String url : activeRelays.keySet()) {
            disconnectRelay(url);
        }
        activeRelays.clear();
        Log.i(TAG, "All relay connections terminated.");
    }

    public int getConnectedRelayCount() {
        return activeRelays.size();
    }
}