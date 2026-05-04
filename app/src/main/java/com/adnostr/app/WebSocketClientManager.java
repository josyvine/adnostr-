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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Decentralized Network Manager.
 * UPDATED: Implements detailed technical logging of raw Nostr JSON traffic 
 * to identify why ads or search reach may be failing.
 * FIXED: Subscription logic now uses dynamic IDs to prevent relay rejection.
 * FIXED: Included Kind 5 (Deletions) in the subscription filter so the foreground app can wipe deleted ads.
 * FORENSIC UPDATE: Implemented race-condition fix for already-connected relays.
 * CRITICAL FIX FOR POPUP: Converted to Multi-Listener Observer Pattern to prevent Fragments 
 * from overwriting the Global MainActivity ad listener.
 * CRASH FIX: Enforced all listener callbacks on the Main Thread to prevent CalledFromWrongThreadException.
 * ENHANCEMENT: Implemented master console switch and Professional vs. Debug log filtering.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Schema Observer: Added a high-level hook to notify the system when new crowdsourced metadata arrives.
 * - Forensic Sniffing: Automatic identification and dispatch of Kind 30006/30007 events.
 * - REPAIR UPDATE: NOTICE and CLOSED frames are now routed to listeners for forensic error reporting.
 * 
 * PERFORMANCE FIX (ANTI-HANG):
 * - Background Schema Notification: SchemaEventListener notifications are now dispatched 
 *   on the background WebSocket thread. This allows ReportActivity to perform heavy 
 *   database archival without blocking the Main UI thread.
 */
public class WebSocketClientManager {

    private static final String TAG = "AdNostr_WSManager";
    private static WebSocketClientManager instance;
    private Context appContext;

    // Thread-safe map of active relay connections (URL -> Client)
    private final Map<String, WebSocketClient> activeRelays = new ConcurrentHashMap<>();

    // Technical live log for the detailed technical popup console
    private final StringBuilder liveLogs = new StringBuilder();
    // Millisecond precision for forensic debugging
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // FIXED: Support for multiple listeners so Activity and Fragments don't collide
    private final List<RelayStatusListener> listeners = new CopyOnWriteArrayList<>();

    // ADMIN SUPREMACY: Dedicated listeners for crowdsourced schema events
    private final List<SchemaEventListener> schemaListeners = new CopyOnWriteArrayList<>();
    
    // CRASH FIX: Handler to dispatch events to the Main UI Thread
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public interface RelayStatusListener {
        void onRelayConnected(String url);
        void onRelayDisconnected(String url, String reason);
        void onMessageReceived(String url, String message);
        void onError(String url, Exception ex);
    }

    /**
     * ADMIN SUPREMACY: Interface to handle pre-parsed crowdsourced data.
     */
    public interface SchemaEventListener {
        void onSchemaEventReceived(String url, JSONObject event);
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

    /**
     * UPDATED: Adds a listener to the pool instead of overwriting existing ones.
     */
    public void addStatusListener(RelayStatusListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * UPDATED: Removes a specific listener to prevent memory leaks.
     */
    public void removeStatusListener(RelayStatusListener listener) {
        listeners.remove(listener);
    }

    /**
     * ADMIN SUPREMACY: Register a component to monitor global metadata changes.
     */
    public void addSchemaListener(SchemaEventListener listener) {
        if (listener != null && !schemaListeners.contains(listener)) {
            schemaListeners.add(listener);
        }
    }

    public void removeSchemaListener(SchemaEventListener listener) {
        schemaListeners.remove(listener);
    }

    /**
     * Legacy support: Adds the listener to the list.
     */
    public void setStatusListener(RelayStatusListener listener) {
        addStatusListener(listener);
    }

    /**
     * Records a timestamped technical event for the big detailed popup.
     * UPDATED: Now respects the Global Console Enable switch and Debug Mode filter.
     */
    private synchronized void addToLog(String message) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(appContext);

        // 1. MASTER KILL SWITCH: If console is disabled, discard everything and clear RAM
        if (!db.isConsoleLogEnabled()) {
            if (liveLogs.length() > 0) liveLogs.setLength(0);
            return;
        }

        String logOutput = message;

        // 2. PROFESSIONAL FILTER: If Debug mode is OFF, summarize protocol frames
        if (!db.isDebugModeActive()) {
            if (message.contains("FRAME_RECV FROM")) {
                logOutput = "Inbound Network Packet received from Relay node.";
            } else if (message.contains("FRAME_SEND (REQ)")) {
                logOutput = "Subscription request: Synchronizing interests with Network.";
            } else if (message.contains("FRAME_SEND (EVENT)")) {
                logOutput = "Outbound Cryptographic Signature sent to Network.";
            } else if (message.contains("FRAME_SEND (MANUAL_REQ)")) {
                logOutput = "Manual Discovery: Scanning directory for matching profiles.";
            } else if (message.startsWith("[\"NOTICE\"")) {
                logOutput = "Relay issued a notification or status update.";
            } else if (message.startsWith("[\"CLOSED\"")) {
                logOutput = "Relay terminated a specific subscription channel.";
            }
        }

        String time = timeFormat.format(new Date());
        // PREPEND to the log so the newest network events appear at the top
        liveLogs.insert(0, "[" + time + "] " + logOutput + "\n\n");
        if (liveLogs.length() > 60000) {
            liveLogs.setLength(50000); // Prevent memory bloat while keeping 50k chars of history
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

        addToLog("NETWORK_POOL: Initiating connections to " + relayUrls.size() + " decentralized nodes.");
        for (String url : relayUrls) {
            connectRelay(url);
        }
    }

    /**
     * Attempts to connect to a decentralized relay if not already active.
     * FIXED: Added race-condition logic to handle already-connected sockets immediately.
     */
    public void connectRelay(final String relayUrl) {
        if (relayUrl == null || !relayUrl.startsWith("wss://")) return;

        if (activeRelays.containsKey(relayUrl)) {
            WebSocketClient existing = activeRelays.get(relayUrl);
            if (existing != null && existing.isOpen()) {
                // FORENSIC LOG: Identifying existing tunnel reuse
                addToLog("TCP_REUSE: Re-using existing open tunnel for " + relayUrl);
                
                // CRITICAL FIX: Notify all registered listeners on the Main Thread
                mHandler.post(() -> {
                    for (RelayStatusListener listener : listeners) {
                        listener.onRelayConnected(relayUrl);
                    }
                });

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

                    addToLog("TCP_OPEN: Connection successful to " + relayUrl + "\nHandshake: 101 Switching Protocols");

                    // Subscribe to user interests on connection open
                    subscribeToUserInterests(this, relayUrl);

                    // CRASH FIX: Dispatch to listeners on Main Thread
                    mHandler.post(() -> {
                        for (RelayStatusListener listener : listeners) {
                            listener.onRelayConnected(relayUrl);
                        }
                    });
                }

                @Override
                public void onMessage(String message) {
                    // FORENSIC: Capture raw incoming JSON frame
                    addToLog("FRAME_RECV FROM [" + relayUrl + "]:\n" + message);

                    // ADMIN SUPREMACY: Pre-parse schema events to notify observers efficiently
                    try {
                        if (message.startsWith("[")) {
                            JSONArray msgArray = new JSONArray(message);
                            String type = msgArray.getString(0);

                            if ("EVENT".equals(type)) {
                                JSONObject event = msgArray.getJSONObject(2);
                                int kind = event.optInt("kind", -1);
                                if (kind == 30006 || kind == 30007) {
                                    // PERFORMANCE FIX: We no longer post to mHandler (Main Thread) here.
                                    // Observers are notified on the background thread to prevent UI hangs.
                                    for (SchemaEventListener schemaListener : schemaListeners) {
                                        schemaListener.onSchemaEventReceived(relayUrl, event);
                                    }
                                }
                            }
                            // REPAIR UPDATE: Route NOTICE error frames to forensic listeners
                            else if ("NOTICE".equals(type)) {
                                String detail = msgArray.optString(1, "Relay status update");
                                JSONObject noticeEv = new JSONObject();
                                noticeEv.put("id", "ntc-" + UUID.randomUUID().toString());
                                noticeEv.put("kind", -1); // Internal kind for errors
                                noticeEv.put("content", "RELAY_NOTICE: " + detail);
                                noticeEv.put("pubkey", "system");
                                noticeEv.put("created_at", System.currentTimeMillis() / 1000);

                                // PERFORMANCE FIX: Notify background observers
                                for (SchemaEventListener schemaListener : schemaListeners) {
                                    schemaListener.onSchemaEventReceived(relayUrl, noticeEv);
                                }
                            }
                            // REPAIR UPDATE: Route CLOSED frames (Rate Limiting/Auth) to forensic listeners
                            else if ("CLOSED".equals(type)) {
                                String reason = msgArray.optString(2, "Subscription closed by relay");
                                JSONObject closedEv = new JSONObject();
                                closedEv.put("id", "cls-" + UUID.randomUUID().toString());
                                closedEv.put("kind", -2); // Internal kind for errors
                                closedEv.put("content", "RELAY_CLOSED: " + reason);
                                closedEv.put("pubkey", "system");
                                closedEv.put("created_at", System.currentTimeMillis() / 1000);

                                // PERFORMANCE FIX: Notify background observers
                                for (SchemaEventListener schemaListener : schemaListeners) {
                                    schemaListener.onSchemaEventReceived(relayUrl, closedEv);
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    // CRASH FIX: Dispatch status updates to listeners on Main Thread for UI compatibility
                    mHandler.post(() -> {
                        for (RelayStatusListener listener : listeners) {
                            listener.onMessageReceived(relayUrl, message);
                        }
                    });
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Relay Closed [" + relayUrl + "]: " + reason);
                    activeRelays.remove(relayUrl);
                    
                    // FORENSIC: Identifying termination source
                    String source = remote ? "Remote Relay" : "Local App";
                    addToLog("TCP_CLOSED: " + relayUrl + "\nCode: " + code + "\nReason: " + reason + "\nSource: " + source);
                    
                    // CRASH FIX: Dispatch to listeners on Main Thread
                    mHandler.post(() -> {
                        for (RelayStatusListener listener : listeners) {
                            listener.onRelayDisconnected(relayUrl, reason);
                        }
                    });
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "Relay Failure [" + relayUrl + "]: " + ex.getMessage());
                    activeRelays.remove(relayUrl);
                    
                    // FORENSIC: Full stack trace for identifying socket timeouts or SSL issues
                    addToLog("NETWORK_ERROR: " + relayUrl + "\nException: " + ex.toString());
                    
                    // CRASH FIX: Dispatch to listeners on Main Thread
                    mHandler.post(() -> {
                        for (RelayStatusListener listener : listeners) {
                            listener.onError(relayUrl, ex);
                        }
                    });
                }
            };

            client.setConnectionLostTimeout(30); 
            client.connect();

        } catch (Exception e) {
            addToLog("SETUP_CRITICAL: Initial WebSocket failure for " + relayUrl + "\nError: " + e.getMessage());
            Log.e(TAG, "Initial WebSocket setup failed for " + relayUrl + ": " + e.getMessage());
        }
    }

    /**
     * Subscribes the device to Kind 30001 Ad events matching the User's hashtags.
     * FIXED: Uses a dynamic UUID subID to prevent relay duplicate rejection.
     * FIXED: Now also requests Kind 5 (Deletions) so the app can wipe deleted ads.
     */
    public void subscribeToUserInterests(WebSocketClient client, String url) {
        if (client == null || !client.isOpen()) return;

        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(appContext);

            if (!db.isListening()) {
                addToLog("MONITORING_PAUSED: Monitoring is OFF. Skipping subscription for " + url);
                return;
            }

            Set<String> interests = db.getInterests();

            if (interests.isEmpty()) {
                addToLog("SUBSCRIPTION_WARNING: Interest list is empty. No filters sent to " + url);
                return;
            }

            // 1. Sanitize hashtags for Nostr protocol
            JSONArray tagArray = new JSONArray();
            for (String tag : interests) {
                tagArray.put(tag.toLowerCase().replace("#", ""));
            }

            // 2. Build the Kind 30001 (Ads) and Kind 5 (Deletions) filter
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001).put(5));
            filter.put("#t", tagArray);

            // 3. Construct REQ with a unique Subscription ID
            String subId = "ad-" + UUID.randomUUID().toString().substring(0, 8);
            
            JSONArray req = new JSONArray();
            req.put("REQ");
            req.put(subId);
            req.put(filter);

            String rawJson = req.toString();
            client.send(rawJson);

            // FORENSIC: Log the exact subscription message sent
            addToLog("FRAME_SEND (REQ) TO [" + url + "]:\n" + rawJson);

        } catch (Exception e) {
            addToLog("SUB_ERROR: JSON construction failed. " + e.getMessage());
        }
    }

    /**
     * Broadcasts subscription REQ to all currently connected relays.
     */
    public void resubscribeAll() {
        addToLog("SYSTEM_EVENT: Global resubscription triggered by UI state change.");
        for (Map.Entry<String, WebSocketClient> entry : activeRelays.entrySet()) {
            subscribeToUserInterests(entry.getValue(), entry.getKey());
        }
    }

    /**
     * Broadcasts a signed event and logs the full outgoing JSON payload.
     */
    public void broadcastEvent(String eventJson) {
        if (activeRelays.isEmpty()) {
            addToLog("BROADCAST_FAILURE: 0 active relay connections. Payload discarded.");
            return;
        }

        String nostrMessage = "[\"EVENT\"," + eventJson + "]";

        int sentCount = 0;
        for (Map.Entry<String, WebSocketClient> entry : activeRelays.entrySet()) {
            WebSocketClient client = entry.getValue();
            if (client != null && client.isOpen()) {
                client.send(nostrMessage);
                sentCount++;
                // FORENSIC: Trace transmission to each node in the pool
                addToLog("FRAME_SEND (EVENT) TO [" + entry.getKey() + "]:\n" + nostrMessage);
            }
        }
        Log.d(TAG, "Broadcast completed to " + sentCount + " relays.");
    }

    public void subscribe(String relayUrl, String subscriptionJson) {
        WebSocketClient client = activeRelays.get(relayUrl);
        if (client != null && client.isOpen()) {
            client.send(subscriptionJson);
            // FORENSIC: Trace manual REQ (Browse/Nearby/Directory)
            addToLog("FRAME_SEND (MANUAL_REQ) TO [" + relayUrl + "]:\n" + subscriptionJson);
        } else {
            addToLog("REQ_FAILED: Relay [" + relayUrl + "] is not connected.");
        }
    }

    public void disconnectRelay(String relayUrl) {
        WebSocketClient client = activeRelays.remove(relayUrl);
        if (client != null) {
            client.close();
            addToLog("MANUAL_DISCONNECT: Closed tunnel to " + relayUrl);
        }
    }

    public void shutdown() {
        for (String url : activeRelays.keySet()) {
            disconnectRelay(url);
        }
        activeRelays.clear();
        addToLog("SYSTEM_SHUTDOWN: All WebSocket connections have been terminated.");
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