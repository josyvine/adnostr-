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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DECENTRALIZED MARKETPLACE SCHEMA ENGINE
 * Handles crowdsourced category and specification creation by Advertisers.
 * Syncs Kind 30006 (Schema Updates) and Kind 30007 (Value Pools) across the network.
 * UPDATED: Implements Kind 5 (Deletion) processing to ensure deleted items never return.
 * UPDATED: Implements Hardcoded Category Overrides to allow global deletion of built-in UI items.
 * UPDATED: Implements Cascading Deletion to wipe Fields and Value Pools (Brands) when a Category is deleted.
 * 
 * CROWDSOURCED DATA FIX:
 * - Increased CountDownLatch to 15 seconds to prevent data "vanishing" on slow relays.
 * - Implemented Schema Caching: Loads data from local memory first, then updates from network.
 * 
 * COLLECTIVE MEMORY UPDATE:
 * - fetchGlobalSchema: Now detects Network Amnesia and triggers Auto-Healing to restore pruned relay data.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Executioner Authority: Admin can target and delete events authored by ANY user.
 * - Persistence Gate: fetchGlobalSchema strictly cross-references the local WIPED_SCHEMA_IDS blocklist.
 * 
 * VOLATILITY & SEQUENTIAL HEALING FIX:
 * - executeSequentialHealing: Logic to re-inject data in Tiered Order (Cat -> Sub -> Spec -> Value).
 * - Fresh Timestamping: Reset relay pruning clocks by re-signing with current time.
 * 
 * INSTANT DROPDOWN SYNC (NEW):
 * - fetchGlobalSchema Integration: Now merges the Immutable Forensic Archive into the final 
 *   output JSON. This ensures dropdowns populate instantly from local memory while the 
 *   network syncs in the background.
 * - REPAIR UPDATE: fetchGlobalSchema now performs deep merger of hard-locked local data.
 */
public class MarketplaceSchemaManager {

    private static final String TAG = "AdNostr_SchemaManager";

    public interface SchemaFetchCallback {
        void onSchemaFetched(String schemaJson);
    }

    /**
     * NEW: Callback to report status of the background healing engine.
     */
    public interface HealerCallback {
        void onHealingComplete(int restoredCount);
    }

    /**
     * Fetches all crowdsourced categories, fields, and historical values from the network.
     * NEW: Also fetches Kind 5 events to identify and purge deleted entries.
     * FIXED: Now utilizes Local Cache first and has an extended 15-second network window.
     * UPDATED LOGIC: Internally merges the Forensic Archive to fix empty UI dropdowns.
     */
    public static void fetchGlobalSchema(Context context, SchemaFetchCallback callback) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

            // =========================================================================
            // STEP 1: PERSISTENCE FIRST (LOCAL MEMORY LOAD)
            // Immediately return cached data so the UI is never empty.
            // =========================================================================
            String cachedSchema = db.getSchemaCache();
            if (cachedSchema != null && !cachedSchema.equals("{}")) {
                Log.i(TAG, "MEMORY_ENGINE: Loading schema from local anchor.");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched(cachedSchema);
                });
            }

            Set<String> relays = db.getRelayPool();
            // INCREASED TIMEOUT: From 5 to 15 seconds to ensure deep relay search success
            final CountDownLatch latch = new CountDownLatch(relays.size());

            // Temporary storage for events coming off the wire
            final List<JSONObject> categoryEvents = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> fieldEvents = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> valueEvents = Collections.synchronizedList(new ArrayList<>());

            // Set to track IDs that MUST be purged (Kind 5 targets)
            final Set<String> deletedEventIds = Collections.synchronizedSet(new HashSet<>());
            // Set to track Hardcoded Category names that the network has "Deleted"
            final Set<String> hiddenHardcodedNames = Collections.synchronizedSet(new HashSet<>());

            try {
                // Request Kind 30006 (Schema), 30007 (Values), AND Kind 5 (Deletions)
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30006).put(30007).put(5));

                String subId = "schema-sync-" + UUID.randomUUID().toString().substring(0, 6);
                String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

                for (String url : relays) {
                    try {
                        WebSocketClient client = new WebSocketClient(new URI(url)) {
                            @Override
                            public void onOpen(ServerHandshake handshakedata) {
                                send(req);
                            }

                            @Override
                            public void onMessage(String message) {
                                try {
                                    if (!message.startsWith("[")) return;
                                    JSONArray msgArray = new JSONArray(message);
                                    if ("EVENT".equals(msgArray.getString(0))) {
                                        JSONObject event = msgArray.getJSONObject(2);
                                        int kind = event.getInt("kind");
                                        String eventId = event.getString("id");

                                        // PART 1: Process Deletions (Kind 5)
                                        if (kind == 5) {
                                            JSONArray tags = event.optJSONArray("tags");
                                            if (tags != null) {
                                                for (int i = 0; i < tags.length(); i++) {
                                                    JSONArray tag = tags.getJSONArray(i);
                                                    if (tag.length() >= 2) {
                                                        if ("e".equals(tag.getString(0))) {
                                                            // Mark this Event ID for purging
                                                            deletedEventIds.add(tag.getString(1));
                                                        } else if ("hardcoded_name".equals(tag.getString(0))) {
                                                            // Mark a built-in category (like "Cars") for hiding
                                                            hiddenHardcodedNames.add(tag.getString(1));
                                                        }
                                                    }
                                                }
                                            }
                                        } 
                                        // PART 2: Collect Schema Data
                                        else {
                                            String contentStr = event.getString("content");
                                            if (contentStr.startsWith("{")) {
                                                JSONObject content = new JSONObject(contentStr);
                                                content.put("_event_id", eventId); // Attach ID for filtering

                                                if (kind == 30006) {
                                                    String type = content.optString("type", "");
                                                    if ("category".equals(type)) categoryEvents.add(content);
                                                    else if ("field".equals(type)) fieldEvents.add(content);
                                                } else if (kind == 30007) {
                                                    valueEvents.add(content);
                                                }
                                            }
                                        }
                                    } else if ("EOSE".equals(msgArray.getString(0))) {
                                        close();
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override public void onClose(int c, String r, boolean m) { latch.countDown(); }
                            @Override public void onError(Exception ex) { latch.countDown(); }
                        };
                        client.setConnectionLostTimeout(15); // Extended timeout for slow relays
                        client.connect();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }

                // Wait up to 15 seconds for network consensus
                latch.await(15, TimeUnit.SECONDS);

                // =========================================================================
                // THE FILTER ENGINE: PERMANENT PURGE & ARCHIVE MERGE LOGIC (REWRITE)
                // =========================================================================

                // INSTANT DROPDOWN FIX: Load the Hard-Locked Archive
                String archiveJson = db.getForensicArchive();
                JSONArray archiveArray = new JSONArray(archiveJson);

                // 1. Process Categories (Archive + Network)
                JSONArray filteredCategories = new JSONArray();
                // Phase A: Add from Network
                for (JSONObject cat : categoryEvents) {
                    String eid = cat.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredCategories.put(cat);
                    }
                }
                // Phase B: Merge Unique items from Immutable Archive
                for (int i = 0; i < archiveArray.length(); i++) {
                    JSONObject arcEvent = archiveArray.getJSONObject(i);
                    JSONObject content = new JSONObject(arcEvent.getString("content"));
                    String eid = arcEvent.getString("id");
                    if (arcEvent.getInt("kind") == 30006 && "category".equals(content.optString("type"))) {
                        if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid)) {
                            // De-duplicate: Only add if not already in the list
                            boolean exists = false;
                            for(int j=0; j<filteredCategories.length(); j++) {
                                if(filteredCategories.getJSONObject(j).optString("sub").equalsIgnoreCase(content.optString("sub").trim())) {
                                    exists = true; break;
                                }
                            }
                            if(!exists) { content.put("_event_id", eid); filteredCategories.put(content); }
                        }
                    }
                }

                // 2. Process Fields (Archive + Network)
                JSONArray filteredFields = new JSONArray();
                for (JSONObject field : fieldEvents) {
                    String eid = field.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredFields.put(field);
                    }
                }
                for (int i = 0; i < archiveArray.length(); i++) {
                    JSONObject arcEvent = archiveArray.getJSONObject(i);
                    JSONObject content = new JSONObject(arcEvent.getString("content"));
                    String eid = arcEvent.getString("id");
                    if (arcEvent.getInt("kind") == 30006 && "field".equals(content.optString("type"))) {
                        if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid)) {
                            boolean exists = false;
                            for(int j=0; j<filteredFields.length(); j++) {
                                if(filteredFields.getJSONObject(j).optString("id").equals(content.optString("id"))) {
                                    exists = true; break;
                                }
                            }
                            if(!exists) { content.put("_event_id", eid); filteredFields.put(content); }
                        }
                    }
                }

                // 3. Process Value Pools (Archive + Network)
                JSONArray filteredValues = new JSONArray();
                for (JSONObject val : valueEvents) {
                    String eid = val.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredValues.put(val);
                    }
                }
                for (int i = 0; i < archiveArray.length(); i++) {
                    JSONObject arcEvent = archiveArray.getJSONObject(i);
                    JSONObject content = new JSONObject(arcEvent.getString("content"));
                    String eid = arcEvent.getString("id");
                    if (arcEvent.getInt("kind") == 30007) {
                        if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid)) {
                            // Merge all archived value pools to ensure Bajaj/Audi dropdowns are never empty
                            content.put("_event_id", eid); 
                            filteredValues.put(content);
                        }
                    }
                }

                // Package everything into the master JSON for HTML injection
                JSONObject globalSchema = new JSONObject();
                globalSchema.put("categories", filteredCategories);
                globalSchema.put("fields", filteredFields);
                globalSchema.put("values", filteredValues);

                // INJECT: Merge Local Hidden Names with Network Deletion tags
                Set<String> allHidden = db.getHiddenHardcodedNames();
                allHidden.addAll(hiddenHardcodedNames);
                globalSchema.put("hidden_hardcoded", new JSONArray(allHidden));

                // =========================================================================
                // STEP 2: RE-CACHE & AUTO-HEAL (COLLECTIVE MEMORY UPDATE)
                // =========================================================================

                // Detection: Did the network return nothing but we have something saved?
                boolean networkEmpty = (categoryEvents.isEmpty() && fieldEvents.isEmpty() && valueEvents.isEmpty());
                boolean anchorValid = (cachedSchema != null && cachedSchema.length() > 50);

                if (networkEmpty && anchorValid) {
                    Log.w(TAG, "COLLECTIVE MEMORY: Network amnesia detected. Triggering Healing Sequence...");
                    executeSequentialHealing(context, null);
                } else {
                    // Normal state: Update memory with the latest consensus
                    db.saveSchemaCache(globalSchema.toString());
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched(globalSchema.toString());
                });

            } catch (Exception e) {
                Log.e(TAG, "Schema Fetch Error: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched("{}");
                });
            }
        }).start();
    }

    /**
     * VOLATILITY FIX: THE SEQUENTIAL HEALING ENGINE
     * Logic: Broadcasts the immutable archive in a tiered hierarchy with 
     * timing delays to ensure proper relay indexing.
     * UPDATED: Accepts TechnicalLogListener to feed the forensic console.
     */
    public static void executeSequentialHealing(Context context, SequentialBroadcastQueue.TechnicalLogListener listener) {
        new Thread(() -> {
            Log.w(TAG, "HEALER: Commencing tiered network restoration...");
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

            // Create the dedicated queue orchestrator
            SequentialBroadcastQueue queue = new SequentialBroadcastQueue(context);

            // Link the listener if provided (usually from ReportActivity)
            if (listener != null) {
                queue.setTechnicalLogListener(listener);
            }

            // Pull the HARD-LOCKED source of truth
            String archiveJson = db.getForensicArchive();

            // REPAIR UPDATE: Strengthened guard clause to handle empty strings and safety reset
            if (archiveJson == null || archiveJson.trim().isEmpty() || archiveJson.equals("[]")) {
                if (listener != null) {
                    listener.onLogGenerated("SYSTEM: Archive is empty. Restoration aborted.");
                }
                return;
            }

            // REPAIR UPDATE: Check preparation success before proceeding with broadcast
            if (queue.prepareArchive(archiveJson)) {
                queue.startBroadcast();
            } else {
                if (listener != null) {
                    listener.onLogGenerated("ABORTED: Restoration stopped due to empty or corrupt archive.");
                }
            }

        }).start();
    }

    /**
     * Logic: Resets the pruning clock on relays by generating a fresh signature.
     * REMOVED: redundant reBroadcastWithFreshTime (Logic moved to SequentialBroadcastQueue)
     */

    /**
     * Broadcasts a new Category added by this advertiser.
     */
    public static void broadcastNewCategory(Context context, String mainCat, String subCat) {
        try {
            JSONObject content = new JSONObject();
            content.put("type", "category");
            content.put("main", mainCat);
            content.put("sub", subCat);
            broadcastEvent(context, 30006, content, null);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Category Error: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a new Technical Specification Field.
     */
    public static void broadcastNewField(Context context, String category, String fieldLabel) {
        try {
            JSONObject content = new JSONObject();
            content.put("type", "field");
            content.put("category", category);
            String fieldId = fieldLabel.trim().toLowerCase().replace(" ", "_").replaceAll("[^a-z0-9_]", "");
            content.put("id", fieldId);
            content.put("label", fieldLabel.trim());
            content.put("input_type", "text");
            broadcastEvent(context, 30006, content, null);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Field Error: " + e.getMessage());
        }
    }

    /**
     * GLOBAL DELETE: Scans for the original event and issues a Kind 5 Deletion.
     */
    public static void broadcastFieldDeletion(Context context, String category, String fieldLabel) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
            String myPubKey = db.getPublicKey();
            final List<String> eventIdsToDelete = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(relays.size());

            try {
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30006));
                if (!db.isAdmin()) {
                    filter.put("authors", new JSONArray().put(myPubKey));
                }

                String subId = "find-field-" + UUID.randomUUID().toString().substring(0, 4);
                String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

                for (String url : relays) {
                    try {
                        WebSocketClient client = new WebSocketClient(new URI(url)) {
                            @Override public void onOpen(ServerHandshake h) { send(req); }
                            @Override public void onMessage(String message) {
                                try {
                                    if (!message.startsWith("[")) return;
                                    JSONArray msg = new JSONArray(message);
                                    if ("EVENT".equals(msg.getString(0))) {
                                        JSONObject event = msg.getJSONObject(2);
                                        JSONObject content = new JSONObject(event.getString("content"));
                                        if (category.equals(content.optString("category")) && 
                                            fieldLabel.equals(content.optString("label"))) {
                                            eventIdsToDelete.add(event.getString("id"));
                                        }
                                    } else if ("EOSE".equals(msg.getString(0))) { close(); }
                                } catch (Exception ignored) {}
                            }
                            @Override public void onClose(int c, String r, boolean m) { latch.countDown(); }
                            @Override public void onError(Exception e) { latch.countDown(); }
                        };
                        client.connect();
                    } catch (Exception e) { latch.countDown(); }
                }

                latch.await(5, TimeUnit.SECONDS);
                if (!eventIdsToDelete.isEmpty()) {
                    issueKind5(context, eventIdsToDelete, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Deletion Broadcast Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * CASCADING GLOBAL CATEGORY DELETE: Wipes Category, associated Fields, and associated Value Pools (Brands).
     */
    public static void broadcastCategoryDeletion(Context context, String categoryName) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
            String myPubKey = db.getPublicKey();
            final List<String> eventIdsToDelete = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(relays.size());

            try {
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30006).put(30007));
                if (!db.isAdmin()) {
                    filter.put("authors", new JSONArray().put(myPubKey));
                }

                String subId = "del-cascade-" + UUID.randomUUID().toString().substring(0, 4);
                String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

                for (String url : relays) {
                    try {
                        WebSocketClient client = new WebSocketClient(new URI(url)) {
                            @Override public void onOpen(ServerHandshake h) { send(req); }
                            @Override public void onMessage(String message) {
                                try {
                                    if (!message.startsWith("[")) return;
                                    JSONArray msg = new JSONArray(message);
                                    if ("EVENT".equals(msg.getString(0))) {
                                        JSONObject event = msg.getJSONObject(2);
                                        JSONObject content = new JSONObject(event.getString("content"));
                                        boolean isMatch = categoryName.equals(content.optString("sub")) || 
                                                          categoryName.equals(content.optString("category"));
                                        if (isMatch) { eventIdsToDelete.add(event.getString("id")); }
                                    } else if ("EOSE".equals(msg.getString(0))) { close(); }
                                } catch (Exception ignored) {}
                            }
                            @Override public void onClose(int c, String r, boolean m) { latch.countDown(); }
                            @Override public void onError(Exception e) { latch.countDown(); }
                        };
                        client.connect();
                    } catch (Exception e) { latch.countDown(); }
                }

                latch.await(6, TimeUnit.SECONDS);
                issueKind5(context, eventIdsToDelete, categoryName);
            } catch (Exception e) {
                Log.e(TAG, "Category cascading wipe failure: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Internal helper to broadcast Kind 5 Deletion events.
     */
    private static void issueKind5(Context context, List<String> ids, String hardcodedName) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            JSONObject delEvent = new JSONObject();
            delEvent.put("kind", 5);
            delEvent.put("pubkey", db.getPublicKey());
            delEvent.put("created_at", System.currentTimeMillis() / 1000);
            delEvent.put("content", "AdNostr Schema Cleanup");

            JSONArray tags = new JSONArray();
            for (String id : ids) {
                tags.put(new JSONArray().put("e").put(id));
                db.addWipedSchemaId(id);
            }
            if (hardcodedName != null) {
                tags.put(new JSONArray().put("hardcoded_name").put(hardcodedName));
                db.addHiddenHardcodedName(hardcodedName);
            }
            delEvent.put("tags", tags);

            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), delEvent);
            if (signed != null) {
                NostrPublisher.publishToPool(db.getRelayPool(), signed, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Kind 5 issuance failed: " + e.getMessage());
        }
    }

    public static void broadcastSpecValues(Context context, String category, JSONObject specs) {
        try {
            if (specs == null || specs.length() == 0) return;
            JSONObject content = new JSONObject();
            content.put("category", category);
            content.put("specs", specs);
            broadcastEvent(context, 30007, content, null);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Spec Values Error: " + e.getMessage());
        }
    }

    public static void broadcastBulkValues(Context context, String category, String fieldId, String commaSeparatedValues, String contextField, String contextValue) {
        try {
            if (commaSeparatedValues == null || commaSeparatedValues.trim().isEmpty()) return;
            JSONArray valuesArray = new JSONArray();
            String[] parts = commaSeparatedValues.split(",");
            for (String part : parts) {
                String cleanPart = part.trim();
                if (!cleanPart.isEmpty()) valuesArray.put(cleanPart);
            }
            if (valuesArray.length() == 0) return;

            JSONObject specs = new JSONObject();
            specs.put(fieldId, valuesArray);
            JSONObject content = new JSONObject();
            content.put("category", category);
            content.put("specs", specs);

            if (contextField != null && !contextField.isEmpty() && contextValue != null && !contextValue.isEmpty()) {
                JSONObject contextObj = new JSONObject();
                contextObj.put("field", contextField);
                contextObj.put("value", contextValue);
                content.put("context", contextObj);
            }
            broadcastEvent(context, 30007, content, null);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Bulk Values Error: " + e.getMessage());
        }
    }

    private static void broadcastEvent(Context context, int kind, JSONObject contentJson, String dTagValue) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            JSONObject event = new JSONObject();
            event.put("kind", kind);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", contentJson.toString());

            JSONArray tags = new JSONArray();
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put(dTagValue != null ? dTagValue : "adnostr_schema_" + UUID.randomUUID().toString().substring(0, 8));
            tags.put(dTag);
            event.put("tags", tags);

            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signedEvent != null) {
                NostrPublisher.publishToPool(db.getRelayPool(), signedEvent, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Schema Signing Error: " + e.getMessage());
        }
    }
}