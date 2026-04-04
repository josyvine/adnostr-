package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decentralized Network Manager.
 * UPDATED: Added a "Live Log" feature to record all relay traffic for the technical console.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;

    // Thread-safe map of active relay connections (URL -> Client)
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();

    // NEW: Technical log for the monitoring console
    private final StringBuilder liveLogs = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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
     * Adds a timestamped entry to the technical live log.
     */
    private synchronized void addToLog(String message) {
        String time = timeFormat.format(new Date());
        liveLogs.insert(0, "[" + time + "] " + message + "\n");
        // Keep logs at a reasonable length
        if (liveLogs.length() > 10000) {
            liveLogs.setLength(8000);
        }
    }

    public String getLiveLogs() {
        return liveLogs.toString();
    }

    public void clearLogs() {
        liveLogs.setLength(0);
    }

    /**
     * Connects to a set of relays simultaneously.
     */
    public void connectPool(Set<String> relayUrls) {
        if (relayUrls == null || relayUrls.isEmpty()) return;

        addToLog("POOL: Initiating connection to " + relayUrls.size() + " relays.");
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
                    
                    addToLog("CONNECTED: " + relayUrl);

                    // NEW: Automatically subscribe to matching ads upon connection
                    subscribeToUserInterests(this, relayUrl);

                    if (statusListener != null) {
                        statusListener.onRelayConnected(relayUrl);
                    }
                }

                @Override
                public void onMessage(String message) {
                    // Record incoming ad packets in the technical log
                    if (message.contains("EVENT")) {
                        addToLog("INCOMING from " + relayUrl + ": Ad Event Detected");
                    }
                    
                    if (statusListener != null) {
                        statusListener.onMessageReceived(relayUrl, message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Relay Closed [" + relayUrl + "]: " + reason);
                    activeRelays.remove(relayUrl);
                    addToLog("DISCONNECTED: " + relayUrl + " (" + reason + ")");
                    if (statusListener != null) {
                        statusListener.onRelayDisconnected(relayUrl, reason);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Relay Failure [" + relayUrl + "]: " + ex.getMessage());
                    activeRelays.remove(relayUrl);
                    addToLog("ERROR: " + relayUrl + " - " + ex.getMessage());
                    if (statusListener != null) {
                        statusListener.onError(relayUrl, ex);
                    }
                }
            };

            client.setConnectionLostTimeout(30); 
            client.connect();

        } catch (Exception e) {
            addToLog("CRITICAL: Setup failed for " + relayUrl);
            Log.e(TAG, "Initial WebSocket setup failed for " + relayUrl + ": " + e.getMessage());
        }
    }

    /**
     * NEW: Generates a Nostr REQ (Subscription) based on the user's saved hashtags.
     * This makes the relay push matching ads to the device.
     */
    private void subscribeToUserInterests(WebSocketClient client, String url) {
        try {
            // Fetch interests from database helper
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(null);
            Set<String> interests = db.getInterests();

            if (interests.isEmpty()) {
                addToLog("SUBSCRIPTION: No interests selected, listening for nothing on " + url);
                return;
            }

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

            String reqString = req.toString();
            client.send(reqString);
            
            addToLog("SUBSCRIBED on " + url + " for Kind 1 tags: " + tagArray.toString());

        } catch (Exception e) {
            addToLog("SUBSCRIPTION ERROR: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a signed Nostr event JSON to all active relays in the pool.
     */
    public void broadcastEvent(String eventJson) {
        if (activeRelays.isEmpty()) {
            addToLog("BROADCAST FAILED: No active relays connected.");
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
        addToLog("BROADCAST: Sent event to " + sentCount + " relays.");
    }

    public void subscribe(String relayUrl, String subscriptionJson) {
        WebSocketClient client = activeRelays.get(relayUrl);
        if (client != null && client.isOpen()) {
            client.send(subscriptionJson);
            addToLog("REQ SENT to " + relayUrl);
        }
    }

    public void disconnectRelay(String relayUrl) {
        WebSocketClient client = activeRelays.remove(relayUrl);
        if (client != null) {
            client.close();
            addToLog("MANUAL CLOSE: " + relayUrl);
        }
    }

    public void shutdown() {
        for (String url : activeRelays.keySet()) {
            disconnectRelay(url);
        }
        activeRelays.clear();
        addToLog("SYSTEM: All connections terminated.");
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