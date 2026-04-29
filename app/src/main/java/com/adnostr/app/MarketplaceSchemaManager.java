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
 */
public class MarketplaceSchemaManager {

    private static final String TAG = "AdNostr_SchemaManager";

    public interface SchemaFetchCallback {
        void onSchemaFetched(String schemaJson);
    }

    /**
     * Fetches all crowdsourced categories, fields, and historical values from the network.
     * NEW: Also fetches Kind 5 events to identify and purge deleted entries.
     */
    public static void fetchGlobalSchema(Context context, SchemaFetchCallback callback) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
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
                        client.setConnectionLostTimeout(10);
                        client.connect();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }

                // Wait up to 5 seconds for network consensus
                latch.await(5, TimeUnit.SECONDS);

                // =========================================================================
                // THE FILTER ENGINE: PERMANENT PURGE LOGIC
                // =========================================================================

                // 1. Filter Categories (Check Kind 5 set AND Local Database Blocklist)
                JSONArray filteredCategories = new JSONArray();
                for (JSONObject cat : categoryEvents) {
                    String eid = cat.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredCategories.put(cat);
                    }
                }

                // 2. Filter Fields (Check Kind 5 set AND Local Database Blocklist)
                JSONArray filteredFields = new JSONArray();
                for (JSONObject field : fieldEvents) {
                    String eid = field.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredFields.put(field);
                    }
                }

                // 3. Filter Value Pools (Check Kind 5 set AND Local Database Blocklist)
                JSONArray filteredValues = new JSONArray();
                for (JSONObject val : valueEvents) {
                    String eid = val.optString("_event_id");
                    if (!deletedEventIds.contains(eid) && !db.isSchemaWiped(eid)) {
                        filteredValues.put(val);
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
                filter.put("authors", new JSONArray().put(myPubKey));

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
                    Log.i(TAG, "Permanent Deletion Broadcasted for field: " + fieldLabel);
                }
            } catch (Exception e) {
                Log.e(TAG, "Deletion Broadcast Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * CASCADING GLOBAL CATEGORY DELETE: Wipes Category, associated Fields, and associated Value Pools (Brands).
     * UPDATED: Now searches Kind 30006 AND 30007 for any items matching the category name.
     */
    public static void broadcastCategoryDeletion(Context context, String categoryName) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
            String myPubKey = db.getPublicKey();
            final List<String> eventIdsToDelete = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(relays.size());

            try {
                // DEEP SCAN: Request Kind 30006 (Schema/Fields) AND 30007 (Value Pools/Brands)
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30006).put(30007));
                filter.put("authors", new JSONArray().put(myPubKey));

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
                                        
                                        // Match if it's the Category definition, a Field in that category, or a Value Pool for that category
                                        boolean isMatch = categoryName.equals(content.optString("sub")) || 
                                                          categoryName.equals(content.optString("category"));
                                        
                                        if (isMatch) {
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

                latch.await(6, TimeUnit.SECONDS);

                // Broadcast bulk Kind 5 for all found IDs and the hardcoded name override
                issueKind5(context, eventIdsToDelete, categoryName);
                Log.i(TAG, "Cascading Global Wipe initiated for category: " + categoryName);

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
            // Tags for Event IDs
            for (String id : ids) {
                tags.put(new JSONArray().put("e").put(id));
                db.addWipedSchemaId(id); // Block ID permanently in local DB blocklist
            }
            // Tag for Hardcoded Override
            if (hardcodedName != null) {
                tags.put(new JSONArray().put("hardcoded_name").put(hardcodedName));
                db.addHiddenHardcodedName(hardcodedName); // Block hardcoded name in local DB
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