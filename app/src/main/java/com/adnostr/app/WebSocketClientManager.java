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
 * UPDATED: Implements detailed technical logging of raw Nostr JSON traffic 
 * to identify why ads or search reach may be failing.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;

    // Thread-safe map of active relay connections (URL -> Client)
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();

    // Technical live log for the detailed technical popup console
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
     * Records a timestamped technical event for the big detailed popup.
     */
    private synchronized void addToLog(String message) {
        String time = timeFormat.format(new Date());
        liveLogs.insert(0, "[" + time + "] " + message + "\n\n");
        // FIXED: Increased buffer size from 15k to 50k characters so full JSON logs aren't deleted before you can read them.
        if (liveLogs.length() > 50000) {
            liveLogs.setLength(40000);
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

        addToLog("NETWORK: Connecting to pool of " + relayUrls.size() + " relays.");
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

                    // NEW: Subscribing to user interests and logging the action
                    subscribeToUserInterests(this, relayUrl);

                    if (statusListener != null) {
                        statusListener.onRelayConnected(relayUrl);
                    }
                }

                @Override
                public void onMessage(String message) {
                    // FIXED: Removed the 300 character truncation so you can see the full JSON array, tags, and signatures.
                    addToLog("RECV from " + relayUrl + ":\n" + message);

                    if (statusListener != null) {
                        statusListener.onMessageReceived(relayUrl, message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Relay Closed [" + relayUrl + "]: " + reason);
                    activeRelays.remove(relayUrl);
                    addToLog("CLOSED: " + relayUrl + " (Reason: " + reason + ")");
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
     * Subscribes the device to Kind 1 Ad events matching the User's hashtags.
     * Technical logs record the raw REQ JSON.
     */
    private void subscribeToUserInterests(WebSocketClient client, String url) {
        try {
            // Must pass context from somewhere or handle singleton correctly
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(null);
            Set<String> interests = db.getInterests();
            String userPubkey = db.getPublicKey();

            addToLog("IDENTITY: User Pubkey is " + userPubkey);

            if (interests.isEmpty()) {
                addToLog("SUBSCRIPTION: Empty interest list for " + url);
                return;
            }

            // 1. Sanitize hashtags for Nostr protocol (remove #)
            JSONArray tagArray = new JSONArray();
            for (String tag : interests) {
                tagArray.put(tag.toLowerCase().replace("#", ""));
            }

            // 2. Build the Kind 1 Ad filter
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(1));
            filter.put("#t", tagArray);

            // 3. Construct REQ command
            JSONArray req = new JSONArray();
            req.put("REQ");
            req.put("adnostr_sub_v1");
            req.put(filter);

            String rawJson = req.toString();
            client.send(rawJson);

            addToLog("SENT REQ to " + url + ":\n" + rawJson);

        } catch (Exception e) {
            addToLog("SUB ERROR: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a signed event and logs the full outgoing JSON payload.
     */
    public void broadcastEvent(String eventJson) {
        if (activeRelays.isEmpty()) {
            addToLog("BROADCAST FAILED: No active connections available.");
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
        addToLog("BROADCAST: Sent to " + sentCount + " relays.\nPAYLOAD: " + eventJson);
    }

    public void subscribe(String relayUrl, String subscriptionJson) {
        WebSocketClient client = activeRelays.get(relayUrl);
        if (client != null && client.isOpen()) {
            client.send(subscriptionJson);
            addToLog("MANUAL REQ to " + relayUrl + ":\n" + subscriptionJson);
        }
    }

    public void disconnectRelay(String relayUrl) {
        WebSocketClient client = activeRelays.remove(relayUrl);
        if (client != null) {
            client.close();
            addToLog("MANUAL DISCONNECT: " + relayUrl);
        }
    }

    public void shutdown() {
        for (String url : activeRelays.keySet()) {
            disconnectRelay(url);
        }
        activeRelays.clear();
        addToLog("SYSTEM: All relay connections terminated.");
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