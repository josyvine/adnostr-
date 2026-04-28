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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DECENTRALIZED MARKETPLACE SCHEMA ENGINE
 * Handles crowdsourced category and specification creation by Advertisers.
 * Syncs Kind 30006 (Schema Updates) and Kind 30007 (Value Pools) across the network.
 */
public class MarketplaceSchemaManager {

    private static final String TAG = "AdNostr_SchemaManager";

    public interface SchemaFetchCallback {
        void onSchemaFetched(String schemaJson);
    }

    /**
     * Fetches all crowdsourced categories, fields, and historical values from the network.
     * Merges them into a single JSON block to inject into the HTML WebView.
     */
    public static void fetchGlobalSchema(Context context, SchemaFetchCallback callback) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
            final CountDownLatch latch = new CountDownLatch(relays.size());

            // Thread-safe lists to accumulate global crowdsourced data
            final List<JSONObject> newCategories = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> newFields = Collections.synchronizedList(new ArrayList<>());
            final List<JSONObject> valuePools = Collections.synchronizedList(new ArrayList<>());

            try {
                // Request Kind 30006 (Schema) and 30007 (Values)
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30006).put(30007));
                
                String subId = "schema-" + UUID.randomUUID().toString().substring(0, 6);
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
                                        String contentStr = event.getString("content");
                                        
                                        if (contentStr.startsWith("{")) {
                                            JSONObject content = new JSONObject(contentStr);
                                            if (kind == 30006) {
                                                String type = content.optString("type", "");
                                                if ("category".equals(type)) newCategories.add(content);
                                                else if ("field".equals(type)) newFields.add(content);
                                            } else if (kind == 30007) {
                                                valuePools.add(content);
                                            }
                                        }
                                    } else if ("EOSE".equals(msgArray.getString(0))) {
                                        close();
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onClose(int code, String reason, boolean remote) {
                                latch.countDown();
                            }

                            @Override
                            public void onError(Exception ex) {
                                latch.countDown();
                            }
                        };
                        client.setConnectionLostTimeout(10);
                        client.connect();
                    } catch (Exception e) {
                        latch.countDown();
                    }
                }

                // Wait up to 5 seconds for network consensus
                latch.await(5, TimeUnit.SECONDS);

                // Package the gathered data into a single master JSON
                JSONObject globalSchema = new JSONObject();
                globalSchema.put("categories", new JSONArray(newCategories));
                globalSchema.put("fields", new JSONArray(newFields));
                globalSchema.put("values", new JSONArray(valuePools));

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
     * Broadcasts a new Category added by this advertiser to the rest of the network.
     */
    public static void broadcastNewCategory(Context context, String mainCat, String subCat) {
        try {
            JSONObject content = new JSONObject();
            content.put("type", "category");
            content.put("main", mainCat);
            content.put("sub", subCat);
            broadcastEvent(context, 30006, content);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Category Error: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a new Technical Specification Field added by this advertiser.
     */
    public static void broadcastNewField(Context context, String category, String fieldLabel) {
        try {
            JSONObject content = new JSONObject();
            content.put("type", "field");
            content.put("category", category);
            // Convert label "KM Driven" to ID "km_driven"
            String fieldId = fieldLabel.trim().toLowerCase().replace(" ", "_").replaceAll("[^a-z0-9_]", "");
            content.put("id", fieldId);
            content.put("label", fieldLabel.trim());
            content.put("input_type", "text"); // Default to text to allow flexible inputs
            broadcastEvent(context, 30006, content);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Field Error: " + e.getMessage());
        }
    }

    /**
     * FEATURE FIX: Deletes a technical field permanently from Nostr using Kind 5.
     * Logic: Finds the original Kind 30006 event and issues a deletion request.
     */
    public static void broadcastFieldDeletion(Context context, String category, String fieldLabel) {
        new Thread(() -> {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            Set<String> relays = db.getRelayPool();
            String myPubKey = db.getPublicKey();
            final List<String> eventIdsToDelete = Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(relays.size());

            try {
                // Step 1: Find the original Kind 30006 event created by ME
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

                // Step 2: Issue Kind 5 Deletion for all matching IDs found
                if (!eventIdsToDelete.isEmpty()) {
                    JSONObject delEvent = new JSONObject();
                    delEvent.put("kind", 5);
                    delEvent.put("pubkey", myPubKey);
                    delEvent.put("created_at", System.currentTimeMillis() / 1000);
                    delEvent.put("content", "Deleting schema field: " + fieldLabel);

                    JSONArray tags = new JSONArray();
                    for (String id : eventIdsToDelete) {
                        JSONArray tag = new JSONArray().put("e").put(id);
                        tags.put(tag);
                    }
                    delEvent.put("tags", tags);

                    JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), delEvent);
                    if (signed != null) {
                        NostrPublisher.publishToPool(relays, signed, null);
                        Log.i(TAG, "Permanent Deletion Broadcasted for field: " + fieldLabel);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Deletion Broadcast Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Broadcasts typed spec values so other advertisers get auto-complete suggestions.
     * This is triggered when an advertiser publishes an entire ad.
     */
    public static void broadcastSpecValues(Context context, String category, JSONObject specs) {
        try {
            if (specs == null || specs.length() == 0) return;
            JSONObject content = new JSONObject();
            content.put("category", category);
            content.put("specs", specs);
            broadcastEvent(context, 30007, content);
        } catch (Exception e) {
            Log.e(TAG, "Broadcast Spec Values Error: " + e.getMessage());
        }
    }

    /**
     * ENHANCEMENT 2: Bulk Value Seeding
     * Takes a comma-separated string from the HTML modal, splits it, and pushes it 
     * instantly to the network to populate auto-complete dropdowns for all users.
     */
    public static void broadcastBulkValues(Context context, String category, String fieldId, String commaSeparatedValues) {
        try {
            if (commaSeparatedValues == null || commaSeparatedValues.trim().isEmpty()) return;

            JSONArray valuesArray = new JSONArray();
            String[] parts = commaSeparatedValues.split(",");
            
            for (String part : parts) {
                String cleanPart = part.trim();
                if (!cleanPart.isEmpty()) {
                    valuesArray.put(cleanPart);
                }
            }

            if (valuesArray.length() == 0) return;

            // Package the array into the expected 'specs' format
            JSONObject specs = new JSONObject();
            specs.put(fieldId, valuesArray);

            JSONObject content = new JSONObject();
            content.put("category", category);
            content.put("specs", specs);

            // Broadcast as a standard Kind 30007 Value Pool event
            broadcastEvent(context, 30007, content);
            Log.i(TAG, "Bulk Values Broadcasted for field '" + fieldId + "' (" + valuesArray.length() + " items)");

        } catch (Exception e) {
            Log.e(TAG, "Broadcast Bulk Values Error: " + e.getMessage());
        }
    }

    /**
     * Internal helper to sign and push the event to the relay pool.
     */
    private static void broadcastEvent(Context context, int kind, JSONObject contentJson) {
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
            dTag.put("adnostr_schema_" + UUID.randomUUID().toString().substring(0, 8));
            tags.put(dTag);
            event.put("tags", tags);

            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            if (signedEvent != null) {
                Set<String> relayPool = db.getRelayPool();
                NostrPublisher.publishToPool(relayPool, signedEvent, null);
                Log.i(TAG, "Crowdsourced Schema Event Broadcasted: Kind " + kind);
            }
        } catch (Exception e) {
            Log.e(TAG, "Schema Signing Error: " + e.getMessage());
        }
    }
}