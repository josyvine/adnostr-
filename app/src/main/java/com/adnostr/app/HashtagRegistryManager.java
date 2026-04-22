package com.adnostr.app;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.DialogMyHashtagsBinding;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DECENTRALIZED HASHTAG REGISTRY ENGINE
 * Implements the Hybrid Hashtag System (Public & Private).
 * FEATURE: Proof-of-Ownership via earliest "created_at" timestamp.
 * FEATURE: Registry Deed d-tag: "adnostr_hashtag_owner:[tag]".
 * FEATURE: Real-time network verification of hashtag availability.
 * UPDATED: Added Hashtag Release logic (Kind 5) for Feature 2.
 */
public class HashtagRegistryManager {

    private static final String TAG = "AdNostr_Registry";

    // Status Constants for Permission Logic
    public static final int STATUS_AVAILABLE = 0; // Case 1: No owner found
    public static final int STATUS_MINE = 1;      // Case 2: Owned by current user
    public static final int STATUS_TAKEN = 2;     // Case 3: Owned by another competitor

    public interface OwnershipCallback {
        void onResult(int status, String ownerPubkey);
    }

    public interface RegistryActionCallback {
        void onComplete(boolean success);
    }

    /**
     * ASYNC: Checks the network to identify the true owner of a hashtag.
     * Searches specifically for the deed d-tag across all relays.
     */
    public static void checkOwnership(Context context, String tag, String myPubkey, OwnershipCallback callback) {
        new Thread(() -> {
            int result = checkOwnershipInternal(context, tag, myPubkey);
            if (callback != null) {
                callback.onResult(result, ""); // Simplified for async UI callback
            }
        }).start();
    }

    /**
     * SYNC: Blocking check for the Background Worker (NostrListenerWorker).
     * Ensures we verify ownership before displaying an ad.
     */
    public static void checkOwnershipSync(Context context, String tag, String myPubkey, OwnershipCallback callback) {
        int result = checkOwnershipInternal(context, tag, myPubkey);
        if (callback != null) callback.onResult(result, "");
    }

    /**
     * Internal Core: Scans the relay pool for the earliest Kind 30001 Deed.
     */
    private static int checkOwnershipInternal(Context context, String tag, String myPubkey) {
        final String targetDTag = "adnostr_hashtag_owner:" + tag.toLowerCase().replace("#", "");
        final List<JSONObject> deedsFound = Collections.synchronizedList(new ArrayList<>());

        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        Set<String> relays = db.getRelayPool();
        final CountDownLatch latch = new CountDownLatch(relays.size());

        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(30001));
            filter.put("#d", new JSONArray().put(targetDTag));

            String subId = "regcheck-" + UUID.randomUUID().toString().substring(0, 6);
            String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

            for (String url : relays) {
                connectAndFetchDeeds(url, req, deedsFound, latch);
            }

            // Wait up to 6 seconds for registry consensus
            latch.await(6, TimeUnit.SECONDS);

            if (deedsFound.isEmpty()) return STATUS_AVAILABLE;

            // FIRST-TO-CLAIM RULE: Sort by oldest timestamp
            Collections.sort(deedsFound, (o1, o2) -> Long.compare(o1.optLong("created_at"), o2.optLong("created_at")));

            JSONObject oldestDeed = deedsFound.get(0);
            String ownerPubkey = oldestDeed.optString("pubkey", "");

            if (ownerPubkey.equalsIgnoreCase(myPubkey)) {
                return STATUS_MINE;
            } else {
                return STATUS_TAKEN;
            }

        } catch (Exception e) {
            Log.e(TAG, "Registry Check Failed: " + e.getMessage());
            return STATUS_AVAILABLE; // Fail-open to public by default
        }
    }

    /**
     * Broadcasts a "Deed" event to claim a hashtag exclusively.
     */
    public static void broadcastDeed(Context context, String tag, String privKey, String pubKey, RegistryActionCallback callback) {
        try {
            String cleanTag = tag.toLowerCase().replace("#", "");
            String deedDTag = "adnostr_hashtag_owner:" + cleanTag;

            JSONObject event = new JSONObject();
            event.put("kind", 30001);
            event.put("pubkey", pubKey);
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", "AdNostr Exclusive Hashtag Deed for #" + cleanTag);

            JSONArray tags = new JSONArray();
            JSONArray dTagPair = new JSONArray();
            dTagPair.put("d");
            dTagPair.put(deedDTag);
            tags.put(dTagPair);
            event.put("tags", tags);

            JSONObject signedDeed = NostrEventSigner.signEvent(privKey, event);

            if (signedDeed != null) {
                Set<String> relayPool = AdNostrDatabaseHelper.getInstance(context).getRelayPool();
                NostrPublisher.publishToPool(relayPool, signedDeed, (relayUrl, success, message) -> {
                    // Logs handled by global publisher
                });

                // Save locally so the app remembers
                AdNostrDatabaseHelper.getInstance(context).addOwnedHashtag(cleanTag);
                if (callback != null) callback.onComplete(true);
            } else {
                if (callback != null) callback.onComplete(false);
            }

        } catch (Exception e) {
            Log.e(TAG, "Deed broadcast failure: " + e.getMessage());
            if (callback != null) callback.onComplete(false);
        }
    }

    /**
     * FEATURE 2: Releases an owned hashtag by broadcasting a Kind 5 Deletion event.
     * Logic: Finds the deed ID on the network, targets it with NIP-09, and wipes local DB.
     */
    public static void releaseDeed(Context context, String tag, String privKey, String pubKey, RegistryActionCallback callback) {
        new Thread(() -> {
            try {
                String cleanTag = tag.toLowerCase().replace("#", "");
                String targetDTag = "adnostr_hashtag_owner:" + cleanTag;
                final List<JSONObject> results = Collections.synchronizedList(new ArrayList<>());

                AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
                Set<String> relays = db.getRelayPool();
                final CountDownLatch latch = new CountDownLatch(relays.size());

                // 1. Find the exact event ID of the current deed
                JSONObject filter = new JSONObject();
                filter.put("kinds", new JSONArray().put(30001));
                filter.put("#d", new JSONArray().put(targetDTag));
                filter.put("authors", new JSONArray().put(pubKey));

                String subId = "find-id-" + UUID.randomUUID().toString().substring(0, 4);
                String req = new JSONArray().put("REQ").put(subId).put(filter).toString();

                for (String url : relays) {
                    connectAndFetchDeeds(url, req, results, latch);
                }
                latch.await(5, TimeUnit.SECONDS);

                if (results.isEmpty()) {
                    if (callback != null) callback.onComplete(false);
                    return;
                }

                String eventIdToDelete = results.get(0).getString("id");

                // 2. Construct Kind 5 (Event Deletion)
                JSONObject deletionEvent = new JSONObject();
                deletionEvent.put("kind", 5);
                deletionEvent.put("pubkey", pubKey);
                deletionEvent.put("created_at", System.currentTimeMillis() / 1000);
                deletionEvent.put("content", "Hashtag #" + cleanTag + " released by owner.");

                JSONArray tags = new JSONArray();
                JSONArray eTagPair = new JSONArray();
                eTagPair.put("e");
                eTagPair.put(eventIdToDelete);
                tags.put(eTagPair);
                deletionEvent.put("tags", tags);

                JSONObject signedDeletion = NostrEventSigner.signEvent(privKey, deletionEvent);

                if (signedDeletion != null) {
                    NostrPublisher.publishToPool(relays, signedDeletion, (relayUrl, success, message) -> {});

                    // 3. Update Local Storage
                    db.removeOwnedHashtag(cleanTag);
                    if (callback != null) callback.onComplete(true);
                } else {
                    if (callback != null) callback.onComplete(false);
                }

            } catch (Exception e) {
                Log.e(TAG, "Release failure: " + e.getMessage());
                if (callback != null) callback.onComplete(false);
            }
        }).start();
    }

    /**
     * Displays the Command Center "My Hashtags" Registry Dialog.
     * UPDATED: Implements list rendering and "Release" button actions (Feature 2).
     */
    public static void showRegistryDialog(Context context, FragmentManager fragmentManager) {
        DialogMyHashtagsBinding binding = DialogMyHashtagsBinding.inflate(LayoutInflater.from(context));
        AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_AdNostr_Dialog)
                .setView(binding.getRoot())
                .create();

        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

        // 1. Set up Registry List logic
        List<String> ownedList = new ArrayList<>(db.getOwnedHashtags());

        if (ownedList.isEmpty()) {
            binding.llEmptyRegistry.setVisibility(View.VISIBLE);
            binding.rvOwnedHashtags.setVisibility(View.GONE);
        } else {
            binding.llEmptyRegistry.setVisibility(View.GONE);
            binding.rvOwnedHashtags.setVisibility(View.VISIBLE);

            binding.rvOwnedHashtags.setLayoutManager(new LinearLayoutManager(context));

            // CRITICAL FIX: Use an array to store the adapter reference. 
            // This allows the listener to access the adapter before the assignment is complete.
            final OwnedHashtagAdapter[] adapterWrapper = new OwnedHashtagAdapter[1];

            adapterWrapper[0] = new OwnedHashtagAdapter(ownedList, new OwnedHashtagAdapter.OnReleaseClickListener() {
                @Override
                public void onReleaseClicked(String tag, int position) {
                    Toast.makeText(context, "Releasing #" + tag + "...", Toast.LENGTH_SHORT).show();

                    releaseDeed(context, tag, db.getPrivateKey(), db.getPublicKey(), success -> {
                        if (success) {
                            ownedList.remove(position);
                            // Ensure UI updates on main thread
                            if (context instanceof android.app.Activity) {
                                ((android.app.Activity) context).runOnUiThread(() -> {
                                    // Use the wrapped reference to notify removal
                                    if (adapterWrapper[0] != null) {
                                        adapterWrapper[0].notifyItemRemoved(position);
                                    }
                                    if (ownedList.isEmpty()) {
                                        binding.llEmptyRegistry.setVisibility(View.VISIBLE);
                                        binding.rvOwnedHashtags.setVisibility(View.GONE);
                                    }
                                    Toast.makeText(context, "Hashtag Released.", Toast.LENGTH_SHORT).show();
                                });
                            }
                        } else {
                            if (context instanceof android.app.Activity) {
                                ((android.app.Activity) context).runOnUiThread(() -> 
                                    Toast.makeText(context, "Release failed. Check network.", Toast.LENGTH_SHORT).show()
                                );
                            }
                        }
                    });
                }
            });
            binding.rvOwnedHashtags.setAdapter(adapterWrapper[0]);
        }

        binding.btnCloseRegistry.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static void connectAndFetchDeeds(String url, String req, List<JSONObject> results, CountDownLatch latch) {
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
                        JSONArray resp = new JSONArray(message);
                        if ("EVENT".equals(resp.getString(0))) {
                            results.add(resp.getJSONObject(2));
                        } else if ("EOSE".equals(resp.getString(0))) {
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
}