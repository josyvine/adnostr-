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
 * FIXED: Toggle now triggers immediate relay resubscription and Ad Popup handling.
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
            
            // 2. FIXED: Force all active relay connections to send a "REQ" for ads immediately
            wsManager.resubscribeAll();
            
            Toast.makeText(getContext(), "Ad monitoring activated!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Ad monitoring paused.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Broadcasts Kind 30001 (User Interests) with BIP-340 Signature.
     */
    private void broadcastUserInterests() {
        Set<String> followed = db.getInterests();
        if (followed.isEmpty()) return;

        try {
            JSONObject event = new JSONObject();
            event.put("kind", 30001); 
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", ""); 

            JSONArray tags = new JSONArray();
            for (String tag : followed) {
                JSONArray tagPair = new JSONArray();
                tagPair.put("t");
                tagPair.put(tag.toLowerCase().replace("#", ""));
                tags.put(tagPair);
            }
            event.put("tags", tags);

            // Sign the event (ensure NostrEventSigner fix has been applied)
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);

            if (signedEvent != null) {
                technicalLogs.append("SIGNING SUCCESS: Interest List Signed.\n");
                wsManager.broadcastEvent(signedEvent.toString());
                technicalLogs.append("BROADCAST: Sent Kind 30001 to relays.\n");
                technicalLogs.append("PAYLOAD: ").append(signedEvent.toString()).append("\n\n");
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
                // FIXED: Handle incoming Ad Events and launch the Popup Activity
                try {
                    if (message.contains("EVENT")) {
                        technicalLogs.append("[INCOMING] Ad detected from ").append(url).append("\n");
                        updateOpenConsole();
                        
                        if (db.isListening()) {
                            // Launch the Full-Screen Ad Overlay
                            Intent intent = new Intent(requireContext(), AdPopupActivity.class);
                            intent.putExtra("AD_PAYLOAD_JSON", message);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ad launch failed: " + e.getMessage());
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

    private void setupHashtagGrid() {
        hashtagPool = new ArrayList<>(db.getAvailableHashtags());
        Set<String> followedInterests = db.getInterests();
        adapter = new HashtagAdapter(hashtagPool, followedInterests, this);
        binding.rvHashtags.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.rvHashtags.setAdapter(adapter);
        updateDeleteButtonVisibility();
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
        wsManager.setStatusListener(null);
        super.onDestroyView();
        binding = null;
    }
}