package com.adnostr.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;

/**
 * Fire-and-Forget Event Broadcaster.
 * UPDATED: Added technical reporting hooks to provide detailed broadcast 
 * information for the Network Console popup.
 * FIXED: Removed synchronous Thread.sleep() to prevent socket blocking, 
 * allowing the Relay ACK to be received properly.
 */
public class NostrPublisher {

    private static final String TAG = "AdNostr_Publisher";

    /**
     * Interface to capture relay-by-relay technical logs.
     */
    public interface PublishListener {
        void onRelayReport(String relayUrl, boolean success, String message);
    }

    /**
     * Publishes a signed Nostr event to a specific relay.
     * 
     * @param relayUrl The wss:// address of the target relay.
     * @param signedEvent The JSONObject containing the signed Nostr event data.
     * @param listener Callback to report success or failure details.
     */
    public static void publishEvent(final String relayUrl, final JSONObject signedEvent, final PublishListener listener) {
        new Thread(() -> {
            try {
                Log.i(TAG, "Initiating broadcast to: " + relayUrl);
                URI uri = new URI(relayUrl);

                WebSocketClient client = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        try {
                            // Construct standard Nostr broadcast message: ["EVENT", {event}]
                            JSONArray message = new JSONArray();
                            message.put("EVENT");
                            message.put(signedEvent);

                            String jsonPayload = message.toString();
                            
                            // Send without blocking the WebSocket thread
                            send(jsonPayload);

                            Log.d(TAG, "Sent to " + relayUrl);

                            // Report the exact JSON and Pubkey to the console for technical identification
                            if (listener != null) {
                                String reportMsg = "PAYLOAD SENT:\n" + signedEvent.toString(2);
                                listener.onRelayReport(relayUrl, true, reportMsg);
                            }

                            // FIX: Close the socket asynchronously after 4 seconds 
                            // to allow time for transmission and the Relay's "OK" ACK.
                            new Thread(() -> {
                                try {
                                    Thread.sleep(4000);
                                    if (!isClosed()) {
                                        close();
                                    }
                                } catch (Exception ignored) {}
                            }).start();

                        } catch (Exception e) {
                            if (listener != null) {
                                listener.onRelayReport(relayUrl, false, "ERROR: Failed during transmission - " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onMessage(String message) {
                        // Captures relay confirmation: ["OK", event_id, true, "msg"]
                        Log.d(TAG, "Response from " + relayUrl + ": " + message);
                        if (listener != null) {
                            // Identifies the raw relay acknowledgement for debugging
                            listener.onRelayReport(relayUrl, true, "RELAY_ACK: " + message);
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        Log.i(TAG, "Broadcast session closed: " + relayUrl);
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "Relay unreachable [" + relayUrl + "]: " + ex.getMessage());
                        if (listener != null) {
                            listener.onRelayReport(relayUrl, false, "OFFLINE: Connection refused or timed out.");
                        }
                    }
                };

                client.setConnectionLostTimeout(20);
                // connect() is used to trigger onOpen where the detailed report is generated
                client.connect();

            } catch (Exception e) {
                Log.e(TAG, "Critical broadcast failure: " + e.getMessage());
                if (listener != null) {
                    listener.onRelayReport(relayUrl, false, "CRITICAL: Protocol setup error.");
                }
            }
        }).start();
    }

    /**
     * Broadcasts a single event to a pool of relays and collects real-time technical logs.
     * 
     * @param relays List of relay URLs.
     * @param signedEvent Fully signed JSON event.
     * @param listener Callback for each relay's result.
     */
    public static void publishToPool(Iterable<String> relays, JSONObject signedEvent, PublishListener listener) {
        for (String url : relays) {
            publishEvent(url, signedEvent, listener);
        }
    }
}