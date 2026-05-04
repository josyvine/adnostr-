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
 * ENHANCEMENT: Implements Master App-Level Decryption to verify AdNostr protocol traffic.
 * ENHANCEMENT: Implements User-Side Trust Filter to verify Ad Sender vs Hashtag Owner.
 * NEW: Saves verified Ads to local User History Database.
 * UPDATED: Integrated Global Schema Deletion monitoring to keep categories in sync in the background.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Background Guard: Sniffs for Kind 30006 and 30007 while the app is closed.
 * - Alert System: Triggers High-Priority System Notifications for new crowdsourced metadata (ADMIN ONLY).
 * 
 * VOLATILITY & DISTRIBUTED MEMORY FIX: 
 * - Forensic Archiving: Every crowdsourced frame (30006/30007) is now passed to the 
 *   Immutable Forensic Archive for ALL Advertiser devices, not just Admin.
 * - This ensures every device acts as a decentralized memory node for the database.
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
        
        // DISTRIBUTED MEMORY FIX: Every Advertiser must sync schema even if interests are empty
        boolean isAdvertiser = RoleSelectionActivity.ROLE_ADVERTISER.equals(db.getUserRole());

        if (interests.isEmpty() && !db.isAdmin() && !isAdvertiser) {
            Log.d(TAG, "No interests selected and not in Advertiser mode. Skipping sync.");
            return Result.success();
        }

        // 3. Coordinate multiple relay connections using a latch
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            // REQ Message construction
            String subId = UUID.randomUUID().toString().substring(0, 8);
            JSONArray reqArray = new JSONArray().put("REQ").put(subId);

            // --- FILTER 1: STANDARD AD & DELETION SNIFFER ---
            JSONObject adFilter = new JSONObject();
            adFilter.put("kinds", new JSONArray().put(30001).put(5));

            if (!interests.isEmpty()) {
                JSONArray tags = new JSONArray();
                for (String tag : interests) {
                    tags.put(tag.toLowerCase().replace("#", ""));
                }
                adFilter.put("#t", tags);
            }
            reqArray.put(adFilter);

            // =========================================================================
            // DISTRIBUTED MEMORY FIX: UNIVERSAL SCHEMA SNIFFER
            // Removed isAdmin() check. Now Advertiser B also requests schema frames.
            // =========================================================================
            if (db.isAdmin() || isAdvertiser) {
                JSONObject adminFilter = new JSONObject();
                adminFilter.put("kinds", new JSONArray().put(30006).put(30007));
                // Pull global events since the last 24 hours to rebuild local archive if empty
                adminFilter.put("since", (System.currentTimeMillis() / 1000) - 86400);
                reqArray.put(adminFilter);
                Log.d(TAG, "Distributed Sniffer: Background Database building active for this Advertiser.");
            }

            String reqMessage = reqArray.toString();

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
     * DISTRIBUTED MEMORY: Processes 30006/30007 for ALL Advertiser devices.
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
            String senderPubkey = event.optString("pubkey", "");

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
                        if (tagPair != null && tagPair.length() >= 2) {
                            String tagName = tagPair.getString(0);
                            String tagValue = tagPair.getString(1);

                            // CASE 1: TARGETING AN EVENT ID (Ads or Crowdsourced Schema)
                            if ("e".equals(tagName)) {
                                // Block for Ads
                                db.addWipedAdId(tagValue);
                                // Block for Schema Persistence
                                db.addWipedSchemaId(tagValue);

                                // Wipe from local ad history if it exists there
                                Set<String> localHistory = db.getUserHistory();
                                for (String savedItem : localHistory) {
                                    if (savedItem.contains("\"id\":\"" + tagValue + "\"")) {
                                        db.deleteFromUserHistory(savedItem);
                                        Log.i(TAG, "Wiped item ID in background: " + tagValue);
                                        break;
                                    }
                                }
                            } 
                            // CASE 2: TARGETING A HARDCODED NAME (Global built-in category deletion)
                            else if ("hardcoded_name".equals(tagName)) {
                                db.addHiddenHardcodedName(tagValue);
                                Log.i(TAG, "Hidden hardcoded category in background: " + tagValue);
                            }
                        }
                    }
                }
                return; // Finished processing Kind 5, exit method
            }

            // =================================================================
            // HANDLE KIND 30006 & 30007: DISTRIBUTED FORENSIC ARCHIVING
            // =================================================================
            if (kind == 30006 || kind == 30007) {

                // =========================================================================
                // DISTRIBUTED MEMORY FIX: UNIVERSAL LOCK
                // We now hard-lock metadata for ALL advertisers. Even if Advertiser B
                // isn't Admin, they will remember your car brands locally.
                // =========================================================================
                db.saveToForensicArchive(event.toString());

                // ADMIN SUPREMACY: Only trigger the high-priority system alert for the Admin device
                if (db.isAdmin()) {
                    long createdAt = event.optLong("created_at", 0);
                    if (createdAt > db.getReportLastSeen()) {
                        String type = (kind == 30006) ? "New Schema Contribution" : "New Value Pool Seeding";
                        showAdminSchemaNotification(type, "Click to review crowdsourced data forensic trace.");
                    }
                }
                return;
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
                String adTag = "";
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
                                } else if ("adnostr_interests".equals(tagValue)) {
                                    return; // STRICT FIX: Ignore user interest lists to prevent crash
                                }
                            }
                            if ("t".equals(tagName)) {
                                adTag = tagValue;
                            }
                        }
                    }
                }

                // If it's not a verified Ad, discard it
                if (!isAdNostrBroadcast) {
                    return;
                }

                // =========================================================================
                // FEATURE 2: MASTER APP-LEVEL DECRYPTION
                // Verify if the payload is wrapped in our Master Protocol Key
                // =========================================================================
                String decryptedJson;
                try {
                    decryptedJson = EncryptionUtils.decryptPayload(contentStr);
                } catch (Exception e) {
                    // Decryption failed: Event is not AdNostr protocol or malformed.
                    return;
                }

                JSONObject content = new JSONObject(decryptedJson);

                // --- PHANTOM AD PREVENTION: CONTENT INTEGRITY CHECK ---
                // Notifications must never fire for ads with empty image arrays or missing titles.
                if (!content.has("title") || content.optString("title").isEmpty()) {
                    Log.d(TAG, "Integrity fail: Missing title. Dropping ad.");
                    return;
                }

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

                // =========================================================================
                // FEATURE 1: USER-SIDE TRUST FILTER (OWNERSHIP CHECK)
                // If tag is owned, verify that Sender Pubkey matches Tag Owner Pubkey
                // =========================================================================
                if (!adTag.isEmpty()) {
                    final String finalId = eventId;
                    final String finalSender = senderPubkey;
                    final String finalDecrypted = decryptedJson;
                    final String finalTitle = content.optString("title", "Local Deal Found");

                    // =========================================================================
                    // GLITCH FIX: Handle HTML and JSON Arrays for Clean Notification Text
                    // =========================================================================
                    String cleanDesc = "A new ad matches your interests.";
                    try {
                        Object descObj = content.opt("desc");
                        String rawDesc = "";

                        if (descObj instanceof JSONArray) {
                            JSONArray descArr = (JSONArray) descObj;
                            if (descArr.length() > 0) {
                                rawDesc = descArr.getString(0); // Take the first text chunk
                            }
                        } else if (descObj instanceof String) {
                            rawDesc = (String) descObj;
                        }

                        if (!rawDesc.isEmpty()) {
                            // Strip HTML tags for standard Android Lock Screen Display
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                cleanDesc = android.text.Html.fromHtml(rawDesc, android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                            } else {
                                cleanDesc = android.text.Html.fromHtml(rawDesc).toString();
                            }
                            // Clean up any weird spacing caused by stripped tags
                            cleanDesc = cleanDesc.trim().replaceAll("\\s+", " ");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse description for notification: " + e.getMessage());
                    }
                    final String finalDesc = cleanDesc;
                    // =========================================================================

                    final JSONObject finalOriginalEvent = event;
                    final String finalAdTag = adTag;

                    HashtagRegistryManager.checkOwnershipSync(context, adTag, senderPubkey, new HashtagRegistryManager.OwnershipCallback() {
                        @Override
                        public void onResult(int status, String ownerPubkey) {
                            // CASE 3: OWNED BY ANOTHER (Spam Detection)
                            if (status == HashtagRegistryManager.STATUS_TAKEN) {
                                Log.w(TAG, "TRUST FILTER: Dropped spoofed ad for #" + finalAdTag + " from " + finalSender);
                                return;
                            }

                            // CASE 1 & 2: Tag is PUBLIC or OWNED BY SENDER
                            saveAndNotifyVerifiedAd(finalId, finalSender, finalDecrypted, finalTitle, finalDesc, finalOriginalEvent, rawMessage);
                        }
                    });
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing incoming ad relay packet: " + e.getMessage());
            // FIXED: We now record the exact JSON parsing error so you can debug the payload format.
            logBackgroundError("JSON Parse Error: " + e.getMessage() + "\nPayload: " + rawMessage);
        }
    }

    /**
     * Helper to persist the verified ad to history and trigger the notification.
     */
    private void saveAndNotifyVerifiedAd(String id, String sender, String decryptedContent, String title, String desc, JSONObject originalEvent, String rawOriginal) {
        try {
            // Re-package the message with decrypted content for local viewing
            JSONObject localStoreEvent = new JSONObject(originalEvent.toString());
            localStoreEvent.put("content", decryptedContent);

            JSONArray localMsg = new JSONArray();
            localMsg.put("EVENT");
            localMsg.put("");
            localMsg.put(localStoreEvent);

            // NEW: Save the verified, non-duplicate, decrypted Ad to local history
            db.saveToUserHistory(localMsg.toString());

            // Launch system notification
            showAdNotification(title, desc, localMsg.toString());
            Log.i(TAG, "Ad Successfully Verified and Notified: " + id);
        } catch (Exception e) {
            Log.e(TAG, "Failed to store verified ad: " + e.getMessage());
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

    /**
     * ADMIN SUPREMACY: Triggers a high-priority alert for schema events.
     */
    private void showAdminSchemaNotification(String title, String message) {
        // Tapping this notification launches the Forensic Report Activity
        Intent intent = new Intent(context, ReportActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AdNostrApplication.AD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_report)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(30006, builder.build());
        }
    }
}