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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background Ad Synchronizer.
 * UPDATED: Fixed relay message extraction and background lifecycle management 
 * to ensure ads are actually received and processed.
 */
public class NostrListenerWorker extends Worker {

    private static final String TAG = "AdNostr_Worker";
    private final AdNostrDatabaseHelper db;
    private final Context context;

    private final String[] BOOTSTRAP_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band"
    };

    public NostrListenerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        this.db = AdNostrDatabaseHelper.getInstance(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Background Ad sync initiated...");

        // 1. Check if the user has enabled ad receiving
        if (!db.isListening()) {
            Log.d(TAG, "Ad monitoring is disabled by user. Skipping sync.");
            return Result.success();
        }

        // 2. Fetch User Interests (Hashtags)
        Set<String> interests = db.getInterests();
        if (interests.isEmpty()) {
            Log.d(TAG, "No interests selected. Skipping sync.");
            return Result.success();
        }

        // 3. Coordinate multiple relay connections using a latch
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            // Build Filter: ["REQ", "sub_id", {"kinds": [30001], "#t": ["tag1", "tag2"]}]
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001));
            
            JSONArray tags = new JSONArray();
            for (String tag : interests) {
                tags.add(tag.toLowerCase());
            }
            filter.put("#t", tags);

            String subId = UUID.randomUUID().toString().substring(0, 8);
            String reqMessage = new JSONArray().put("REQ").put(subId).put(filter).toString();

            // Connect to bootstrap relay
            connectAndListen(BOOTSTRAP_RELAYS[0], reqMessage, latch);

            // Wait up to 15 seconds for incoming events before terminating the worker
            latch.await(15, TimeUnit.SECONDS);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Sync process failed: " + e.getMessage());
            return Result.retry();
        }
    }

    private void connectAndListen(String relayUrl, String reqMessage, CountDownLatch latch) {
        try {
            WebSocketClient client = new WebSocketClient(new URI(relayUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "Connected to: " + relayUrl);
                    send(reqMessage);
                }

                @Override
                public void onMessage(String message) {
                    Log.v(TAG, "Relay data: " + message);
                    processRelayEvent(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "Relay closed. Sync complete.");
                    latch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error: " + ex.getMessage());
                    latch.countDown();
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Relay connection failed: " + e.getMessage());
            latch.countDown();
        }
    }

    /**
     * Parses the relay packet and triggers a notification if it's a valid ad.
     */
    private void processRelayEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;
            
            JSONArray msgArray = new JSONArray(rawMessage);
            if (!"EVENT".equals(msgArray.getString(0))) return;

            // Extract event object from the 3rd index of the Nostr message array
            JSONObject event = msgArray.getJSONObject(2);
            String contentStr = event.getString("content");
            JSONObject content = new JSONObject(contentStr);

            String title = content.optString("title", "Local Deal Found");
            String desc = content.optString("desc", "A new ad matches your interests.");

            // Launch system notification
            showAdNotification(title, desc, rawMessage);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing incoming ad relay packet: " + e.getMessage());
        }
    }

    private void showAdNotification(String title, String message, String fullPayload) {
        Intent intent = new Intent(context, AdPopupActivity.class);
        intent.putExtra("AD_PAYLOAD_JSON", fullPayload);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AdNostrApplication.AD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), builder.build());
        }
    }
}