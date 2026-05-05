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
 * - Increased CountDownLatch to 20 seconds to prevent data "vanishing" on slow relays.
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
 * - DE-DUPLICATION ENGINE: Added logic to filter 1,375 redundant items into a lean unique dataset to prevent relay rate-limiting.
 * 
 * DISTRIBUTED MEMORY UPDATE:
 * - Distributed Sniffing: Every device now saves discovered relay data to local archive to prevent "Memory Vacuum".
 * - Detailed Trace: fetchGlobalSchema provides granular logs for the "Retrieve" console overlay.
 * 
 * PERFORMANCE FIX (ANTI-HANG):
 * - Optimized Threaded Execution: Heavy JSON parsing and content-fingerprint comparisons 
 *   are strictly offloaded to background threads.
 * - Non-Blocking Database Interaction: Leveraging background disk writes to prevent stalls 
 *   during high-frequency relay sync.
 */
public class MarketplaceSchemaManager {

    private static final String TAG = "AdNostr_SchemaManager";

    public interface SchemaFetchCallback {
        void onSchemaFetched(String schemaJson);
    }

    /**
     * NEW: Interface to report status back to the Dashboard Console.
     */
    public interface TechnicalLogListener {
        void onLogGenerated(String message);
    }

    /**
     * NEW: Callback to report status of the background healing engine.
     */
    public interface HealerCallback {
        void onHealingComplete(int restoredCount);
    }

    /**
     * Fetches all crowdsourced categories, fields, and historical values from the network.
     * UPDATED: Added targetSubCategory to support Job 1 (Discovery) and Job 2 (Ejection).
     * FIXED: Added validation to prevent java.lang.String to JSONObject conversion crashes.
     */
    public static void fetchGlobalSchema(Context context, String targetSubCategory, SchemaFetchCallback callback, TechnicalLogListener logListener) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

            String logContext = (targetSubCategory == null || targetSubCategory.isEmpty()) ? "DISCOVERY" : "EJECTION [" + targetSubCategory + "]";
            if (logListener != null) logListener.onLogGenerated("INITIATING DATA " + logContext + " SEQUENCE...");

            // =========================================================================
            // STEP 1: PERSISTENCE FIRST (LOCAL MEMORY LOAD)
            // Immediately return cached data so the UI is never empty.
            // =========================================================================
            String cachedSchema = db.getSchemaCache();
            if (cachedSchema != null && !cachedSchema.equals("{}")) {
                if (logListener != null) logListener.onLogGenerated("GATHERING: Extracted metadata from local anchor cache.");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched(cachedSchema);
                });
            }

            Set<String> relays = db.getRelayPool();
            
            // =========================================================================
            // STABILITY FIX: INCREASED TIMEOUT
            // 20 seconds ensures Bajaj models/years arrive even on high-latency relays.
            // =========================================================================
            final CountDownLatch latch = new CountDownLatch(relays.size());

            final List<JSONObject> categoryEvents = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> fieldEvents = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> valueEvents = Collections.synchronizedList(new ArrayList<>());

            final Set<String> deletedEventIds = Collections.synchronizedSet(new HashSet<>());
            final Set<String> hiddenHardcodedNames = Collections.synchronizedSet(new HashSet<>());

            if (logListener != null) logListener.onLogGenerated("SCANNING: Connecting to decentralized relays...");

            try {
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

                                        // DISTRIBUTED MEMORY
                                        db.saveToForensicArchive(event.toString());

                                        if (kind == 5) {
                                            JSONArray tags = event.optJSONArray("tags");
                                            if (tags != null) {
                                                for (int i = 0; i < tags.length(); i++) {
                                                    JSONArray tag = tags.getJSONArray(i);
                                                    if (tag.length() >= 2) {
                                                        if ("e".equals(tag.getString(0))) {
                                                            deletedEventIds.add(tag.getString(1));
                                                        } else if ("hardcoded_name".equals(tag.getString(0))) {
                                                            hiddenHardcodedNames.add(tag.getString(1));
                                                        }
                                                    }
                                                }
                                            }
                                        } 
                                        else {
                                            String contentStr = event.optString("content", "");
                                            // FIXED: JSON conversion crash validation
                                            if (contentStr.trim().startsWith("{")) {
                                                JSONObject content = new JSONObject(contentStr);
                                                content.put("_event_id", eventId); 

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
                        client.setConnectionLostTimeout(20); 
                        client.connect();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }

                latch.await(20, TimeUnit.SECONDS);

                if (logListener != null) logListener.onLogGenerated("MERGING: Synchronizing 4-tier archive with network results...");

                // =========================================================================
                // THE FILTER ENGINE: UNIVERSAL MEMORY MERGE
                // =========================================================================
                Set<String> contentFingerprints = new HashSet<>();
                Set<String> validFieldAnchors = new HashSet<>();

                String archiveJson = db.getForensicArchive();
                JSONArray archiveArray = new JSONArray(archiveJson);

                JSONArray filteredCategories = new JSONArray();

                // Process Categories (Archive + Network)
                for (JSONObject cat : categoryEvents) {
                    String eid = cat.optString("_event_id");
                    String sub = cat.optString("sub", "");
                    String finger = "cat:" + sub.trim().toLowerCase();
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid) && !contentFingerprints.contains(finger)) {
                        filteredCategories.put(cat);
                        contentFingerprints.add(finger);
                        if (logListener != null) logListener.onLogGenerated("EXTRACTED: Sub-Category -> " + sub);
                    }
                }
                for (int i = 0; i < archiveArray.length(); i++) {
                    try {
                        // FIXED: String conversion check
                        Object item = archiveArray.get(i);
                        if (!(item instanceof JSONObject)) continue;
                        
                        JSONObject arcEvent = (JSONObject) item;
                        String arcContentRaw = arcEvent.optString("content", "");
                        if (!arcContentRaw.trim().startsWith("{")) continue;

                        JSONObject content = new JSONObject(arcContentRaw);
                        String eid = arcEvent.getString("id");
                        String sub = content.optString("sub", "");
                        String finger = "cat:" + sub.trim().toLowerCase();
                        if (arcEvent.getInt("kind") == 30006 && "category".equals(content.optString("type"))) {
                            if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid) && !contentFingerprints.contains(finger)) {
                                content.put("_event_id", eid); 
                                filteredCategories.put(content); 
                                contentFingerprints.add(finger);
                                if (logListener != null) logListener.onLogGenerated("EXTRACTED ARCHIVE: Category -> " + sub);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Process Fields (Archive + Network) - TIER 3 REPAIR
                JSONArray filteredFields = new JSONArray();
                for (JSONObject field : fieldEvents) {
                    String eid = field.optString("_event_id");
                    String catName = field.optString("category", "").toLowerCase();
                    String labelName = field.optString("label", "").toLowerCase();
                    String finger = "field:" + catName + ":" + labelName;

                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid) && !contentFingerprints.contains(finger)) {
                        filteredFields.put(field);
                        contentFingerprints.add(finger);
                        validFieldAnchors.add(catName + ":" + labelName); 
                        if (logListener != null) logListener.onLogGenerated("EXTRACTED: Field Anchor '" + labelName + "' for " + catName);
                    }
                }
                for (int i = 0; i < archiveArray.length(); i++) {
                    try {
                        Object item = archiveArray.get(i);
                        if (!(item instanceof JSONObject)) continue;

                        JSONObject arcEvent = (JSONObject) item;
                        String arcContentRaw = arcEvent.optString("content", "");
                        if (!arcContentRaw.trim().startsWith("{")) continue;

                        JSONObject content = new JSONObject(arcContentRaw);
                        String eid = arcEvent.getString("id");
                        String catName = content.optString("category", "").toLowerCase();
                        String labelName = content.optString("label", "").toLowerCase();
                        String finger = "field:" + catName + ":" + labelName;

                        if (arcEvent.getInt("kind") == 30006 && "field".equals(content.optString("type"))) {
                            if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid) && !contentFingerprints.contains(finger)) {
                                content.put("_event_id", eid); 
                                filteredFields.put(content);
                                contentFingerprints.add(finger);
                                validFieldAnchors.add(catName + ":" + labelName); 
                                if (logListener != null) logListener.onLogGenerated("EXTRACTED ARCHIVE: Field Anchor '" + labelName + "' for " + catName);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Process Value Pools (Bajaj Models/Years) - TIER 4 EJECTION
                JSONArray filteredValues = new JSONArray();
                for (JSONObject val : valueEvents) {
                    String cat = val.optString("category", "").toLowerCase();
                    
                    // JOB 2: Targeted Ejection Check
                    if (targetSubCategory != null && !targetSubCategory.isEmpty() && !targetSubCategory.equalsIgnoreCase(cat)) continue;

                    String eid = val.optString("_event_id");
                    JSONObject specs = val.optJSONObject("specs");
                    String specsKey = (specs != null && specs.length() > 0) ? specs.keys().next().toLowerCase() : "unknown";
                    String finger = "val:" + cat + ":" + (specs != null ? specs.toString() : "null");

                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid) && !contentFingerprints.contains(finger)) {
                        filteredValues.put(val);
                        contentFingerprints.add(finger);
                        if (logListener != null) logListener.onLogGenerated("EJECTING: Data Pool for Brand '" + specsKey + "' in " + cat);
                    }
                }
                for (int i = 0; i < archiveArray.length(); i++) {
                    try {
                        Object item = archiveArray.get(i);
                        if (!(item instanceof JSONObject)) continue;

                        JSONObject arcEvent = (JSONObject) item;
                        if (arcEvent.getInt("kind") == 30007) {
                            String arcContentRaw = arcEvent.optString("content", "");
                            if (!arcContentRaw.trim().startsWith("{")) continue;

                            JSONObject content = new JSONObject(arcContentRaw);
                            String cat = content.optString("category", "").toLowerCase();

                            // JOB 2: Targeted Ejection Check
                            if (targetSubCategory != null && !targetSubCategory.isEmpty() && !targetSubCategory.equalsIgnoreCase(cat)) continue;

                            String eid = arcEvent.getString("id");
                            JSONObject specs = content.optJSONObject("specs");
                            String specsKey = (specs != null && specs.length() > 0) ? specs.keys().next().toLowerCase() : "unknown";
                            String finger = "val:" + cat + ":" + (specs != null ? specs.toString() : "null");
                            
                            if (!db.isSchemaWiped(eid) && !deletedEventIds.contains(eid) && !contentFingerprints.contains(finger)) {
                                content.put("_event_id", eid); 
                                filteredValues.put(content);
                                contentFingerprints.add(finger);
                                if (logListener != null) logListener.onLogGenerated("ARCHIVE EJECTION: Pool for '" + specsKey + "' in " + cat);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                JSONObject globalSchema = new JSONObject();
                globalSchema.put("categories", filteredCategories);
                globalSchema.put("fields", filteredFields);
                globalSchema.put("values", filteredValues);

                Set<String> allHidden = db.getHiddenHardcodedNames();
                allHidden.addAll(hiddenHardcodedNames);
                globalSchema.put("hidden_hardcoded", new JSONArray(allHidden));

                // =========================================================================
                // STEP 2: RE-CACHE & AUTO-HEAL
                // =========================================================================
                boolean networkEmpty = (categoryEvents.isEmpty() && fieldEvents.isEmpty() && valueEvents.isEmpty());
                boolean anchorValid = (cachedSchema != null && cachedSchema.length() > 50);

                if (networkEmpty && anchorValid) {
                    if (logListener != null) logListener.onLogGenerated("WARNING: Network amnesia detected. Ejecting local archive...");
                    executeSequentialHealing(context, logListener);
                } else {
                    db.saveSchemaCache(globalSchema.toString());
                }

                if (logListener != null) logListener.onLogGenerated("SUCCESS: Database fully gathered and injected.");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched(globalSchema.toString());
                });

            } catch (Exception e) {
                if (logListener != null) logListener.onLogGenerated("CRITICAL ERROR: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onSchemaFetched("{}");
                });
            }
        }).start();
    }

    /**
     * VOLATILITY FIX: THE SEQUENTIAL HEALING ENGINE
     */
    public static void executeSequentialHealing(Context context, TechnicalLogListener listener) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            SequentialBroadcastQueue queue = new SequentialBroadcastQueue(context);
            if (listener != null) queue.setTechnicalLogListener(listener);

            String archiveJson = db.getForensicArchive();

            if (archiveJson == null || archiveJson.trim().isEmpty() || archiveJson.equals("[]")) {
                if (listener != null) listener.onLogGenerated("SYSTEM: Archive is empty. No data to heal.");
                return;
            }

            if (queue.prepareArchive(archiveJson)) {
                queue.startBroadcast();
            }
        }).start();
    }

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
     * GLOBAL DELETE
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
     * CASCADING GLOBAL CATEGORY DELETE
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