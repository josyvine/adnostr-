package com.adnostr.app;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background Ad Synchronizer.
 * UPDATED: Fixed relay message extraction and background lifecycle management 
 * to ensure ads are actually received and processed.
 * FIXED: Implemented Kind 30001 filtering and dynamic hashtag matching.
 * FIXED: Added content peeking to ignore empty interest lists in background.
 * FIXED: Added strict 'd' tag validation to prevent crashes from adnostr_interests.
 * FIXED: Implemented Duplicate Checking to stop notification spam for already saved ads.
 * FIXED: Added Kind 5 (NIP-09) listening to honor Advertiser deletions and wipe local history.
 * UPDATED: Integrated Wiped ID Blocklist and Image Integrity checks to kill "Phantom Ads".
 * NEW: Saves verified Ads to local User History Database.
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

    /**
     * NEW: Helper to store background errors so they can be viewed in the UI later.
     */
    private void logBackgroundError(String message) {
        SharedPreferences prefs = context.getSharedPreferences("adnostr_secure_prefs", Context.MODE_PRIVATE);
        String currentLogs = prefs.getString("background_error_logs", "");
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String newLog = "[" + time + "] " + message + "\n" + currentLogs;

        // Keep only the last ~5000 characters to prevent memory bloat
        if (newLog.length() > 5000) {
            newLog = newLog.substring(0, 5000);
        }
        prefs.edit().putString("background_error_logs", newLog).apply();
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
            // FIXED: Request both Kind 30001 (Ads) AND Kind 5 (Deletions) to honor Advertiser wipes
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001).put(5));

            JSONArray tags = new JSONArray();
            for (String tag : interests) {
                // FIXED: Changed .add() to .put() and added .replace("#", "") for protocol matching
                tags.put(tag.toLowerCase().replace("#", ""));
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
            logBackgroundError("Worker Setup Failed: " + e.getMessage());
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
                    logBackgroundError("WebSocket Error (" + relayUrl + "): " + ex.getMessage());
                    latch.countDown();
                }
            };
            client.connect();
        } catch (Exception e) {
            Log.e(TAG, "Relay connection failed: " + e.getMessage());
            logBackgroundError("Connection Failed (" + relayUrl + "): " + e.getMessage());
            latch.countDown();
        }
    }

    /**
     * Parses the relay packet and triggers a notification if it's a valid ad,
     * or processes deletions if it's a Kind 5 wipe.
     */
    private void processRelayEvent(String rawMessage) {
        try {
            if (!rawMessage.startsWith("[")) return;

            JSONArray msgArray = new JSONArray(rawMessage);
            if (!"EVENT".equals(msgArray.getString(0))) return;

            // Extract event object from the 3rd index of the Nostr message array
            JSONObject event = msgArray.getJSONObject(2);
            int kind = event.optInt("kind", -1);
            String eventId = event.optString("id", "");

            // --- PHANTOM AD PREVENTION: BLOCKLIST CHECK ---
            // If the ID is in our permanent wiped list, ignore it immediately.
            if (db.isAdWiped(eventId)) {
                Log.d(TAG, "Dropping Phantom Ad: " + eventId + " is on the blocklist.");
                return;
            }

            // =================================================================
            // HANDLE KIND 5: ADVERTISER DELETIONS (NIP-09)
            // =================================================================
            if (kind == 5) {
                JSONArray tags = event.optJSONArray("tags");
                if (tags != null) {
                    for (int i = 0; i < tags.length(); i++) {
                        JSONArray tagPair = tags.optJSONArray(i);
                        // Find the 'e' tag which points to the deleted Ad ID
                        if (tagPair != null && tagPair.length() >= 2 && "e".equals(tagPair.getString(0))) {
                            String targetDeletedId = tagPair.getString(1);

                            // PHANTOM AD PREVENTION: ADD TO BLOCKLIST
                            db.addWipedAdId(targetDeletedId);

                            // Find this Ad ID in the User's local history and wipe it
                            Set<String> localHistory = db.getUserHistory();
                            for (String savedItem : localHistory) {
                                if (savedItem.contains("\"id\":\"" + targetDeletedId + "\"")) {
                                    db.deleteFromUserHistory(savedItem);
                                    Log.i(TAG, "Honored Advertiser Kind 5 Wipe: Removed Ad " + targetDeletedId);
                                    break;
                                }
                            }
                        }
                    }
                }
                return; // Finished processing Kind 5, exit method
            }

            // =================================================================
            // HANDLE KIND 30001: INCOMING ADS
            // =================================================================
            if (kind == 30001) {
                // FIXED: DUPLICATE CHECK - Prevent Notification Spam
                Set<String> history = db.getUserHistory();
                for (String savedItem : history) {
                    // Check if the exact event ID is already in our database
                    if (savedItem.contains("\"id\":\"" + eventId + "\"")) {
                        Log.d(TAG, "Ad " + eventId + " already exists in history. Dropping duplicate.");
                        return; // Halt processing, do not show notification!
                    }
                }

                // CRITICAL FIX: Peek at the content string before attempting to parse as Ad JSON
                // This prevents background crashes when receiving empty User Interest List events.
                String contentStr = event.optString("content", "");
                if (contentStr.isEmpty()) {
                    return; // Silently ignore events that contain no Ad payload
                }

                // STRICT PROTOCOL FILTERING: Verify the 'd' tag to ensure it's a real Ad Broadcast
                boolean isAdNostrBroadcast = false;
                JSONArray tags = event.optJSONArray("tags");
                if (tags != null) {
                    for (int i = 0; i < tags.length(); i++) {
                        JSONArray tagPair = tags.optJSONArray(i);
                        if (tagPair != null && tagPair.length() >= 2) {
                            String tagName = tagPair.optString(0);
                            String tagValue = tagPair.optString(1);

                            if ("d".equals(tagName)) {
                                if (tagValue.startsWith("adnostr_ad_")) {
                                    isAdNostrBroadcast = true;
                                    break;
                                } else if ("adnostr_interests".equals(tagValue)) {
                                    return; // STRICT FIX: Ignore user interest lists to prevent crash
                                }
                            }
                        }
                    }
                }

                // If it's not a verified Ad, discard it
                if (!isAdNostrBroadcast) {
                    return;
                }

                JSONObject content = new JSONObject(contentStr);

                // Crash Prevention: Ensure title actually exists
                if (!content.has("title")) {
                    return;
                }

                // --- PHANTOM AD PREVENTION: CONTENT INTEGRITY CHECK ---
                // If the ad has been wiped from Cloudflare, the image field will be empty or missing.
                // We drop these events to prevent blank-thumbnail notification spam.
                if (!content.has("image")) {
                    Log.d(TAG, "Integrity fail: Missing image field. Dropping ghost ad.");
                    return;
                }
                
                Object imageObj = content.get("image");
                if (imageObj instanceof JSONArray) {
                    if (((JSONArray) imageObj).length() == 0) {
                        Log.d(TAG, "Integrity fail: Image array empty. Dropping ghost ad.");
                        return;
                    }
                } else if (imageObj instanceof String) {
                    if (((String) imageObj).isEmpty()) return;
                }

                String title = content.optString("title", "Local Deal Found");
                String desc = content.optString("desc", "A new ad matches your interests.");

                // NEW: Save the verified, non-duplicate Ad to the Local User History Database
                db.saveToUserHistory(rawMessage);

                // Launch system notification
                showAdNotification(title, desc, rawMessage);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing incoming ad relay packet: " + e.getMessage());
            // FIXED: We now record the exact JSON parsing error so you can debug the payload format.
            logBackgroundError("JSON Parse Error: " + e.getMessage() + "\nPayload: " + rawMessage);
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