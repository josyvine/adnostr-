package com.adnostr.app;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Background Ad Synchronizer.
 * Connects to decentralized relays, applies hashtag filters, 
 * and notifies the user when a matching deal is broadcasted.
 */
public class NostrListenerWorker extends Worker {

    private static final String TAG = "AdNostr_Worker";
    private final AdNostrDatabaseHelper db;
    private final Context context;

    // Bootstrap relay list for discovery as per technical specs
    private final String[] BOOTSTRAP_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social"
    };

    public NostrListenerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        this.db = AdNostrDatabaseHelper.getInstance(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Background Ad Listener starting execution...");

        try {
            // 1. Fetch User Interests from Local Storage
            Set<String> interests = db.getInterests();
            if (interests.isEmpty()) {
                Log.d(TAG, "No interests selected. Skipping relay sync.");
                return Result.success();
            }

            // 2. Build the Nostr Subscription Filter (REQ)
            // Example: ["REQ", "sub_id", {"kinds": [30001], "#t": ["food", "kochi"]}]
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001)); // Ad Events
            
            JSONArray tags = new JSONArray();
            for (String tag : interests) {
                tags.put(tag.toLowerCase());
            }
            filter.put("#t", tags);

            String subscriptionMessage = new JSONArray()
                    .put("REQ")
                    .put(UUID.randomUUID().toString()) // Random Sub ID
                    .put(filter)
                    .toString();

            // 3. Connect to a bootstrap relay and listen for events
            // In a production app, we would loop through multiple relays.
            // Here we connect to the primary one for the task duration.
            connectAndFetchAds(BOOTSTRAP_RELAYS[0], subscriptionMessage);

            // Allow the worker some time to receive events before closing
            Thread.sleep(10000); // 10 Seconds of active listening

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Background sync failed: " + e.getMessage());
            // This error will be captured by the Global Exception Handler
            return Result.retry();
        }
    }

    /**
     * Establishes a temporary WebSocket connection to the decentralized network.
     */
    private void connectAndFetchAds(String relayUrl, String subscriptionJson) {
        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "Connected to relay: " + relayUrl);
                    send(subscriptionJson);
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Incoming Event: " + message);
                    processNostrMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "Relay connection closed: " + reason);
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket Error: " + ex.getMessage());
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to relay: " + e.getMessage());
        }
    }

    /**
     * Parses the relay message. If it's a valid ad, trigger a notification.
     */
    private void processNostrMessage(String rawMessage) {
        try {
            JSONArray msgArray = new JSONArray(rawMessage);
            String type = msgArray.getString(0);

            if ("EVENT".equals(type)) {
                JSONObject event = msgArray.getJSONObject(2);
                String contentStr = event.getString("content");
                JSONObject contentObj = new JSONObject(contentStr);

                String title = contentObj.optString("title", "New Local Deal");
                String desc = contentObj.optString("desc", "");

                // Trigger a system notification for the matching ad
                showAdNotification(title, desc, rawMessage);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse incoming ad: " + e.getMessage());
        }
    }

    /**
     * Builds and fires a high-priority notification.
     */
    private void showAdNotification(String title, String message, String fullJson) {
        Intent intent = new Intent(context, AdPopupActivity.class);
        intent.putExtra("AD_PAYLOAD_JSON", fullJson);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AdNostrApplication.AD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }
}