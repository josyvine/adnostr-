package com.adnostr.app;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;

/**
 * Fire-and-Forget Event Broadcaster.
 * Handles the one-time transmission of signed Nostr events (Ads or User Interests)
 * to decentralized relays without interfering with the main UI WebSocket manager.
 */
public class NostrPublisher {

    private static final String TAG = "AdNostr_Publisher";

    /**
     * Publishes a signed Nostr event to a specific relay.
     * 
     * @param relayUrl The wss:// address of the target relay.
     * @param signedEvent The JSONObject containing the signed Nostr event data.
     */
    public static void publishEvent(final String relayUrl, final JSONObject signedEvent) {
        new Thread(() -> {
            try {
                Log.i(TAG, "Attempting to publish event to: " + relayUrl);
                URI uri = new URI(relayUrl);

                WebSocketClient client = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        try {
                            // Construct standard Nostr broadcast message: ["EVENT", {event}]
                            JSONArray message = new JSONArray();
                            message.put("EVENT");
                            message.put(signedEvent);

                            // Send the data
                            String jsonMessage = message.toString();
                            send(jsonMessage);
                            
                            Log.d(TAG, "Event transmitted to " + relayUrl + ": " + jsonMessage);

                            // For fire-and-forget, we close the connection immediately after sending
                            // Small delay to ensure the OS buffers the outgoing packet
                            Thread.sleep(1000); 
                            close();
                        } catch (Exception e) {
                            Log.e(TAG, "Broadcast failed during transmission: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onMessage(String message) {
                        // Relays sometimes respond with ["OK", event_id, true, ""]
                        Log.d(TAG, "Relay Response from " + relayUrl + ": " + message);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        Log.i(TAG, "Publisher connection closed for: " + relayUrl);
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "Relay Error [" + relayUrl + "]: " + ex.getMessage());
                    }
                };

                // Use a standard timeout for the connection phase
                client.setConnectionLostTimeout(20);
                
                // connectBlocking ensures we are connected before the code proceeds 
                // within this background thread.
                client.connectBlocking();

            } catch (Exception e) {
                Log.e(TAG, "Critical failure during fire-and-forget publish: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Helper to broadcast a single event to a list of relays simultaneously.
     * 
     * @param relays Set or Array of wss:// relay URLs.
     * @param signedEvent The event to be sent.
     */
    public static void publishToPool(Iterable<String> relays, JSONObject signedEvent) {
        for (String url : relays) {
            publishEvent(url, signedEvent);
        }
    }
}