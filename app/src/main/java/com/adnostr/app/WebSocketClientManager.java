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
 * FIXED: Subscription logic now waits for active interests and listening state.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;
    private Context appContext;

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

    /**
     * Initialization with context to ensure database helper works correctly.
     */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
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
                // Connection exists; try to subscribe if listening
                subscribeToUserInterests(existing, relayUrl);
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

                    // Subscribe to user interests on connection open
                    subscribeToUserInterests(this, relayUrl);

                    if (statusListener != null) {
                        statusListener.onRelayConnected(relayUrl);
                    }
                }

                @Override
                public void onMessage(String message) {
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
     * Subscribes the device to Kind 30001 Ad events matching the User's hashtags.
     * UPDATED: Now verifies listening state and interest count before sending REQ.
     */
    public void subscribeToUserInterests(WebSocketClient client, String url) {
        if (client == null || !client.isOpen()) return;

        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(appContext);
            
            // FIX: If the user hasn't clicked "Start Receiving Ads," don't send REQ yet
            if (!db.isListening()) {
                addToLog("SUBSCRIPTION: Monitoring is OFF. Waiting for user to Start Ads.");
                return;
            }

            Set<String> interests = db.getInterests();
            String userPubkey = db.getPublicKey();

            if (interests.isEmpty()) {
                addToLog("SUBSCRIPTION: Empty interest list for " + url + ". No tags to watch.");
                return;
            }

            // 1. Sanitize hashtags for Nostr protocol
            JSONArray tagArray = new JSONArray();
            for (String tag : interests) {
                tagArray.put(tag.toLowerCase().replace("#", ""));
            }

            // 2. Build the Kind 30001 Ad filter (Matching Advertiser format)
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001));
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
     * Broadcasts subscription REQ to all currently connected relays.
     */
    public void resubscribeAll() {
        addToLog("SYSTEM: Resubscribing all relays to current interest list...");
        for (Map.Entry<String, WebSocketClient> entry : activeRelays.entrySet()) {
            subscribeToUserInterests(entry.getValue(), entry.getKey());
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