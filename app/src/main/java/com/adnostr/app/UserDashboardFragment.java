package com.adnostr.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.adnostr.app.databinding.FragmentUserDashboardBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dashboard for standard AdNostr Users.
 * UPDATED: Implements Kind 30001 signed broadcasting and technical console logging.
 * FIXED: Included mandatory 'd' tag for Kind 30001 compliance to fix relay indexing.
 * UPDATED: broadcastUserInterests now prints "Signing Debug" (k, e, parity) for verification.
 * FIXED: Added strict checks to ignore empty User Interest lists and only trigger on real Ads.
 * FIXED: Enforced UI Thread execution for AdPopup launch to bypass background activity restrictions.
 * FIXED: Embeds optional Username into the Kind 30001 content for Advertiser Reach Discovery.
 * FIXED: Enforced strict manual string construction for content to resolve Event ID mismatch.
 * FIXED: Added Duplicate Checking to prevent continuous popups for ads already seen.
 * FIXED: Implemented Kind 5 (NIP-09) listening to wipe ads deleted by advertisers.
 * ENHANCEMENT: Implements Phantom Ad Blocklist and Master App-Level Decryption.
 * ENHANCEMENT: Integrated User-Side Trust Filter (Hashtag Registry check) for live traffic.
 * ENHANCEMENT: Integrated Identity Header to display restored Username and PubKey from JSON.
 * ENHANCEMENT: Respects Privacy Command Center "Hide Username" flag (Feature 1).
 */
public class UserDashboardFragment extends Fragment implements HashtagAdapter.OnHashtagClickListener {

    private static final String TAG = "AdNostr_UserDash";
    private FragmentUserDashboardBinding binding;
    private AdNostrDatabaseHelper db;
    private HashtagAdapter adapter;
    private List<String> hashtagPool;
    private WebSocketClientManager wsManager;

    // Technical Log Accumulator for the Network Console
    private final StringBuilder technicalLogs = new StringBuilder();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentUserDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        wsManager = WebSocketClientManager.getInstance();

        // Ensure WebSocket Manager is initialized with context for DB access
        wsManager.init(requireContext());

        // ENHANCEMENT: Display the user's decentralized identity (from JSON restore or auto-gen)
        setupIdentityHeader();

        setupHashtagGrid();
        binding.btnAddTag.setOnClickListener(v -> addNewHashtag());
        binding.btnDeleteSelected.setOnClickListener(v -> deleteSelectedHashtags());
        binding.btnStartAds.setOnClickListener(v -> toggleListeningState());

        // Allow user to open the Big Technical Pop-up by tapping the monitoring text
        binding.llListeningState.setOnClickListener(v -> showNetworkConsole());

        // Connect to the full decentralized relay pool
        wsManager.connectPool(db.getRelayPool());
        setupNetworkStatusListener();

        updateListeningUI();
        refreshRelayStatus();
    }

    /**
     * Opens the Technical Report dialog safely.
     */
    private void showNetworkConsole() {
        String fullLog = "USER IDENTITY (HEX):\n" + db.getPublicKey() + "\n\n" +
                         "NETWORK EVENTS:\n" + technicalLogs.toString() + "\n" +
                         "PROTOCOL TRAFFIC (LIVE):\n-------------------\n" +
                         wsManager.getLiveLogs();

        RelayReportDialog dialog = RelayReportDialog.newInstance(
                "AD MONITORING CONSOLE",
                "Connected to " + wsManager.getConnectedRelayCount() + " decentralized nodes",
                fullLog
        );

        dialog.showSafe(getChildFragmentManager(), "USER_CONSOLE");
    }

    /**
     * Dynamically pushes new data to the console if it is currently open on screen.
     */
    private void updateOpenConsole() {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                RelayReportDialog existing = (RelayReportDialog) getChildFragmentManager().findFragmentByTag("USER_CONSOLE");
                if (existing != null) {
                    String fullLog = "USER IDENTITY (HEX):\n" + db.getPublicKey() + "\n\n" +
                                     "NETWORK EVENTS:\n" + technicalLogs.toString() + "\n" +
                                     "PROTOCOL TRAFFIC (LIVE):\n-------------------\n" +
                                     wsManager.getLiveLogs();

                    existing.updateTechnicalLogs(
                            "Connected to " + wsManager.getConnectedRelayCount() + " decentralized nodes", 
                            fullLog
                    );
                }
            });
        }
    }

    private void toggleListeningState() {
        boolean currentState = db.isListening();
        boolean newState = !currentState;

        db.setListeningState(newState);
        updateListeningUI();

        if (newState) {
            // 1. Broadcast presence so Advertisers can find this user
            broadcastUserInterests(); 

            // 2. Force all active relay connections to send a "REQ" for ads immediately
            wsManager.resubscribeAll();

            Toast.makeText(getContext(), "Ad monitoring activated!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Ad monitoring paused.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Broadcasts Kind 30001 (User Interests) with BIP-340 Signature.
     * UPDATED: Clears technicalLogs and prints mathematical signing diagnostics (k, e, parity).
     * ENHANCEMENT: Interests are now wrapped in Master App Encryption for dark pool privacy.
     */
    private void broadcastUserInterests() {
        Set<String> followed = db.getInterests();
        if (followed.isEmpty()) return;

        // Clear the console logs before broadcasting so you only see the result of THIS action
        technicalLogs.setLength(0);
        wsManager.clearLogs();

        try {
            JSONObject event = new JSONObject();
            event.put("kind", 30001); 
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);

            // Re-identify username for reach discovery
            String contentStr = "";
            
            // FEATURE 1: Privacy Check for Username Anonymity
            // Only set contentStr to username if privacy toggle allows it
            if (!db.isUsernameHidden()) {
                String savedName = db.getUsername();
                if (savedName != null && !savedName.isEmpty()) {
                    contentStr = savedName; 
                }
            } else {
                Log.d(TAG, "Privacy: Anonymity active. Username excluded from Kind 30001.");
            }

            // ENHANCEMENT: Master App-Level Encryption
            // Hide user interests/username from external Nostr network
            String secureContent = EncryptionUtils.encryptPayload(contentStr);
            event.put("content", secureContent); 

            JSONArray tags = new JSONArray();

            // Mandatory 'd' tag for Parameterized Replaceable Events (Kind 30001)
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_interests");
            tags.put(dTag);

            for (String tag : followed) {
                JSONArray tagPair = new JSONArray();
                tagPair.put("t");
                tagPair.put(tag.toLowerCase().replace("#", ""));
                tags.put(tagPair);
            }
            event.put("tags", tags);

            technicalLogs.append("INITIATING CRYPTO SIGNING...\n");

            // Sign the event
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);

            if (signedEvent != null) {
                technicalLogs.append("SIGNING SUCCESS: Interest List Signed.\n");

                // NEW: SIGNING DEBUG - Print raw mathematical bytes to console
                technicalLogs.append("-----------------------------\n");
                technicalLogs.append("SIGNING DIAGNOSTICS (BIP-340):\n");
                technicalLogs.append("ID (Hash): ").append(signedEvent.getString("id")).append("\n");
                technicalLogs.append("SIG (R+s): ").append(signedEvent.getString("sig")).append("\n");

                // Pulling diagnostic math from static fields in Signer
                technicalLogs.append("Y-PARITY: ").append(NostrEventSigner.lastParity).append("\n");
                technicalLogs.append("NONCE (k): ").append(NostrEventSigner.lastK).append("\n");
                technicalLogs.append("CHALLENGE (e): ").append(NostrEventSigner.lastE).append("\n");
                technicalLogs.append("-----------------------------\n\n");

                wsManager.broadcastEvent(signedEvent.toString());
                technicalLogs.append("BROADCAST: Sent Encrypted Kind 30001 to relays.\n");
                technicalLogs.append("WAITING FOR RELAY VERIFICATION...\n\n");
                updateOpenConsole();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to broadcast interests: " + e.getMessage());
            technicalLogs.append("CRYPTO ERROR: ").append(e.getMessage()).append("\n");
            updateOpenConsole();
        }
    }

    private void setupNetworkStatusListener() {
        wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
            @Override
            public void onRelayConnected(String url) {
                technicalLogs.append("[CONNECTED] ").append(url).append("\n");
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        refreshRelayStatus();
                        updateOpenConsole();
                    });
                }
            }

            @Override
            public void onRelayDisconnected(String url, String reason) {
                technicalLogs.append("[DISCONNECT] ").append(url).append(" (").append(reason).append(")\n");
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        refreshRelayStatus();
                        updateOpenConsole();
                    });
                }
            }

            @Override
            public void onMessageReceived(String url, String message) {
                // Handle relay confirmation messages for verification
                if (message.contains("OK") && message.contains("true")) {
                    technicalLogs.append("[VERIFIED] Relay accepted your identity: ").append(url).append("\n");
                    updateOpenConsole();
                } else if (message.contains("OK") && message.contains("false")) {
                    technicalLogs.append("[REJECTED] Relay rejected your signature: ").append(url).append("\n");
                    updateOpenConsole();
                }

                // Handle incoming Events
                try {
                    if (message.startsWith("[")) {
                        JSONArray msgArray = new JSONArray(message);
                        if ("EVENT".equals(msgArray.getString(0))) {
                            JSONObject event = msgArray.getJSONObject(2);
                            int kind = event.optInt("kind", -1);
                            String eventId = event.optString("id", "");
                            String senderPubkey = event.optString("pubkey", "");

                            // =========================================================================
                            // FEATURE 3: PHANTOM AD PREVENTION - BLOCKLIST CHECK
                            // =========================================================================
                            if (db.isAdWiped(eventId)) {
                                technicalLogs.append("[DROPPED] Phantom Ad Blocked: ").append(eventId).append("\n");
                                updateOpenConsole();
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

                                            // PHANTOM AD PREVENTION: Register wipe permanently
                                            db.addWipedAdId(targetDeletedId);

                                            // Find this Ad ID in the User's local history and wipe it
                                            Set<String> localHistory = db.getUserHistory();
                                            for (String savedItem : localHistory) {
                                                if (savedItem.contains("\"id\":\"" + targetDeletedId + "\"")) {
                                                    db.deleteFromUserHistory(savedItem);
                                                    technicalLogs.append("[WIPED] Advertiser deleted ad: ").append(targetDeletedId).append("\n");
                                                    updateOpenConsole();
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                                return; // Done processing deletion
                            }

                            // =================================================================
                            // HANDLE KIND 30001: INCOMING ADS
                            // =================================================================
                            if (kind == 30001) {

                                // FIXED: DUPLICATE CHECK - Prevent continuous popup spam
                                Set<String> history = db.getUserHistory();
                                for (String savedItem : history) {
                                    if (savedItem.contains("\"id\":\"" + eventId + "\"")) {
                                        return; 
                                    }
                                }

                                // Double check the 'd' tag to ensure it's an AdNostr broadcast
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
                                                    return; // Ignore interest lists
                                                }
                                            }
                                            if ("t".equals(tagName)) {
                                                adTag = tagValue;
                                            }
                                        }
                                    }
                                }

                                if (!isAdNostrBroadcast) return;

                                // =========================================================================
                                // FEATURE 2: MASTER APP-LEVEL DECRYPTION
                                // Verify if the payload is wrapped in our Master Protocol Key
                                // =========================================================================
                                String contentStr = event.optString("content", "");
                                String decryptedJson;
                                try {
                                    decryptedJson = EncryptionUtils.decryptPayload(contentStr);
                                } catch (Exception e) {
                                    // Decryption failed: Not an AdNostr event
                                    return;
                                }

                                JSONObject content = new JSONObject(decryptedJson);

                                // =========================================================================
                                // FEATURE 3: CONTENT INTEGRITY CHECK
                                // =========================================================================
                                if (!content.has("title") || content.optString("title").isEmpty()) return;

                                Object imageObj = content.opt("image");
                                if (imageObj == null) return;
                                if (imageObj instanceof JSONArray && ((JSONArray) imageObj).length() == 0) return;
                                if (imageObj instanceof String && ((String) imageObj).isEmpty()) return;

                                // =========================================================================
                                // FEATURE 1: USER-SIDE TRUST FILTER (OWNERSHIP CHECK)
                                // =========================================================================
                                if (!adTag.isEmpty()) {
                                    final String finalDecrypted = decryptedJson;
                                    final String finalAdTag = adTag;
                                    final String finalId = eventId;
                                    final String finalSender = senderPubkey;
                                    final JSONObject finalOriginalEvent = event;
                                    final String finalUrl = url;

                                    HashtagRegistryManager.checkOwnership(requireContext(), adTag, senderPubkey, new HashtagRegistryManager.OwnershipCallback() {
                                        @Override
                                        public void onResult(int status, String ownerPubkey) {
                                            if (status == HashtagRegistryManager.STATUS_TAKEN) {
                                                technicalLogs.append("[REJECTED] Trust Filter: Mismatch for #").append(finalAdTag).append("\n");
                                                updateOpenConsole();
                                                return;
                                            }

                                            // PROCEED: Ad is Verified (Public or Owned)
                                            technicalLogs.append("[AD VERIFIED] Valid secure ad from ").append(finalUrl).append("\n");
                                            updateOpenConsole();

                                            handleVerifiedAdDisplay(finalId, finalDecrypted, finalOriginalEvent, message);
                                        }
                                    });
                                }
                            } else {
                                updateOpenConsole();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ad processing failed: " + e.getMessage());
                }
            }

            @Override
            public void onError(String url, Exception ex) {
                technicalLogs.append("[ERROR] ").append(url).append(": ").append(ex.getMessage()).append("\n");
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        refreshRelayStatus();
                        updateOpenConsole();
                    });
                }
            }
        });
    }

    /**
     * Saves the verified ad to history and triggers the popup UI.
     */
    private void handleVerifiedAdDisplay(String id, String decryptedContent, JSONObject originalEvent, String rawOriginal) {
        try {
            // Re-package for local history with decrypted content
            JSONObject localStoreEvent = new JSONObject(originalEvent.toString());
            localStoreEvent.put("content", decryptedContent);

            JSONArray localMsg = new JSONArray();
            localMsg.put("EVENT");
            localMsg.put("");
            localMsg.put(localStoreEvent);

            db.saveToUserHistory(localMsg.toString());

            if (db.isListening() && isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Intent intent = new Intent(requireContext(), AdPopupActivity.class);
                    intent.putExtra("AD_PAYLOAD_JSON", localMsg.toString());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to display verified ad: " + e.getMessage());
        }
    }

    private void setupHashtagGrid() {
        hashtagPool = new ArrayList<>(db.getAvailableHashtags());
        Set<String> followedInterests = db.getInterests();
        adapter = new HashtagAdapter(hashtagPool, followedInterests, this);
        binding.rvHashtags.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.rvHashtags.setAdapter(adapter);
        updateDeleteButtonVisibility();
    }

    /**
     * ENHANCEMENT: Populates the UI header with the user's Username and PubKey.
     * This confirms that the identity from the JSON Passport was loaded correctly.
     */
    private void setupIdentityHeader() {
        String username = db.getUsername();
        String pubKey = db.getPublicKey();

        if (username != null && !username.isEmpty()) {
            binding.tvUsername.setText("Welcome, " + username);
        } else {
            binding.tvUsername.setText("Anonymous User");
        }

        if (pubKey != null && !pubKey.isEmpty()) {
            // Truncate PubKey for a clean display (e.g., ab9e...0662)
            String displayId = "ID: " + pubKey.substring(0, 8) + "..." + pubKey.substring(pubKey.length() - 4);
            binding.tvUserIdentity.setText(displayId);
        }
    }

    private void addNewHashtag() {
        String newTag = binding.etCustomTag.getText().toString().trim().toLowerCase();
        if (newTag.isEmpty()) return;
        if (newTag.startsWith("#")) newTag = newTag.substring(1);

        if (!hashtagPool.contains(newTag)) {
            hashtagPool.add(newTag);
            db.saveAvailableHashtags(new HashSet<>(hashtagPool));
            binding.etCustomTag.setText("");
            adapter.notifyItemInserted(hashtagPool.size() - 1);
        }
    }

    private void deleteSelectedHashtags() {
        Set<String> followed = db.getInterests();
        if (followed.isEmpty()) return;
        hashtagPool.removeAll(followed);
        db.saveAvailableHashtags(new HashSet<>(hashtagPool));
        db.saveInterests(new HashSet<>());
        adapter.notifyDataSetChanged();
        updateDeleteButtonVisibility();
    }

    private void updateListeningUI() {
        boolean isListening = db.isListening();
        if (isListening) {
            binding.llListeningState.setVisibility(View.VISIBLE);
            binding.btnStartAds.setText("STOP RECEIVING ADS");
            binding.btnStartAds.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.hfs_inactive_red));
        } else {
            binding.llListeningState.setVisibility(View.GONE);
            binding.btnStartAds.setText("START RECEIVING ADS");
            binding.btnStartAds.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.hfs_active_blue));
        }
    }

    private void updateDeleteButtonVisibility() {
        binding.btnDeleteSelected.setVisibility(db.getInterests().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshRelayStatus() {
        if (binding == null) return;
        int relayCount = wsManager.getConnectedRelayCount();
        binding.tvRelayStatus.setText("Connected to " + relayCount + " Relays");
    }

    @Override
    public void onHashtagToggled(String hashtag, boolean isSelected) {
        Set<String> currentInterests = new HashSet<>(db.getInterests());
        if (isSelected) currentInterests.add(hashtag);
        else currentInterests.remove(hashtag);
        db.saveInterests(currentInterests);
        updateDeleteButtonVisibility();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}