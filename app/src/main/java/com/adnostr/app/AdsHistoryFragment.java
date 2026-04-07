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
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.FragmentAdsHistoryBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ads History & Deletion System.
 * FEATURE: Handles local history viewing and decentralized deletion (NIP-09).
 * USER MODE: Displays received ads; performs local device deletion only.
 * ADVERTISER MODE: Displays broadcasted ads; performs local deletion AND 
 * generates/signs/broadcasts Nostr Kind 5 Deletion events to the network.
 */
public class AdsHistoryFragment extends Fragment implements AdHistoryAdapter.OnAdHistoryClickListener {

    private static final String TAG = "AdNostr_AdsHistory";
    private FragmentAdsHistoryBinding binding;
    private AdNostrDatabaseHelper db;
    private AdHistoryAdapter adapter;
    private List<String> currentHistoryList;
    private String userRole;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAdsHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        userRole = db.getUserRole();
        currentHistoryList = new ArrayList<>();

        setupRecyclerView();
        loadHistoryData();

        // FAB Logic for bulk deletion
        binding.fabDeleteSelected.setOnClickListener(v -> processDeletion());
    }

    /**
     * Initializes the RecyclerView with the history adapter.
     */
    private void setupRecyclerView() {
        binding.rvAdsHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AdHistoryAdapter(currentHistoryList, this);
        binding.rvAdsHistory.setAdapter(adapter);
    }

    /**
     * Loads the appropriate history based on the active role.
     */
    private void loadHistoryData() {
        currentHistoryList.clear();
        Set<String> savedAds;

        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            savedAds = db.getUserHistory();
            binding.tvHistoryTitle.setText("Ads Received");
        } else {
            savedAds = db.getAdvertiserHistory();
            binding.tvHistoryTitle.setText("Ads Broadcasted");
        }

        if (savedAds != null && !savedAds.isEmpty()) {
            currentHistoryList.addAll(savedAds);
            binding.tvNoHistory.setVisibility(View.GONE);
        } else {
            binding.tvNoHistory.setVisibility(View.VISIBLE);
        }

        adapter.notifyDataSetChanged();
    }

    /**
     * Interface: Handles tapping an ad to re-open it in the Popup Activity.
     */
    @Override
    public void onAdTapped(String adJson) {
        Intent intent = new Intent(requireContext(), AdPopupActivity.class);
        intent.putExtra("AD_PAYLOAD_JSON", adJson);
        startActivity(intent);
    }

    /**
     * Interface: Updates the visibility of the delete FAB based on selection state.
     */
    @Override
    public void onSelectionChanged(int selectedCount) {
        if (selectedCount > 0) {
            binding.fabDeleteSelected.show();
        } else {
            binding.fabDeleteSelected.hide();
        }
    }

    /**
     * Logic to handle the deletion of selected ad events.
     */
    private void processDeletion() {
        List<String> selectedItems = adapter.getSelectedItems();
        if (selectedItems.isEmpty()) return;

        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            // Logic for Users: Local Deletion Only
            for (String ad : selectedItems) {
                db.deleteFromUserHistory(ad);
            }
            Toast.makeText(getContext(), "Selected ads removed locally.", Toast.LENGTH_SHORT).show();
            loadHistoryData();
        } else {
            // Logic for Advertisers: Local Deletion + Network Wipe (Kind 5)
            broadcastNostrDeletion(selectedItems);
        }
    }

    /**
     * ADVERTISER ONLY: Implements NIP-09 (Event Deletion).
     * Generates a Kind 5 event for each selected ad, signs it, and broadcasts it to relays.
     */
    private void broadcastNostrDeletion(List<String> adsToDelete) {
        try {
            for (String fullPayload : adsToDelete) {
                // Extract the Ad Event Object from the full Nostr message array
                JSONArray msgArray = new JSONArray(fullPayload);
                JSONObject adEvent = msgArray.getJSONObject(2);
                String adEventId = adEvent.getString("id");

                // 1. Construct Kind 5 Deletion Event
                JSONObject deletionEvent = new JSONObject();
                deletionEvent.put("kind", 5);
                deletionEvent.put("pubkey", db.getPublicKey());
                deletionEvent.put("created_at", System.currentTimeMillis() / 1000);
                deletionEvent.put("content", "Ad removed by advertiser.");

                // 2. Add the 'e' tag targeting the specific Ad Event ID (NIP-09)
                JSONArray tags = new JSONArray();
                JSONArray eTag = new JSONArray();
                eTag.put("e");
                eTag.put(adEventId);
                tags.put(eTag);
                deletionEvent.put("tags", tags);

                // 3. Sign the Deletion Event with the advertiser's private key
                JSONObject signedDeletion = NostrEventSigner.signEvent(db.getPrivateKey(), deletionEvent);

                if (signedDeletion != null) {
                    // 4. Broadcast to the entire relay pool to wipe the ad from network
                    NostrPublisher.publishToPool(db.getRelayPool(), signedDeletion, (relayUrl, success, message) -> {
                        // Technical logging handled by NostrPublisher
                    });

                    // 5. Cleanup local record
                    db.deleteFromAdvertiserHistory(fullPayload);
                }
            }

            Toast.makeText(getContext(), "Ads deleted locally and broadcasted for network wipe.", Toast.LENGTH_LONG).show();
            loadHistoryData();

        } catch (Exception e) {
            Log.e(TAG, "NIP-09 Deletion Failure: " + e.getMessage());
            Toast.makeText(getContext(), "Deletion failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}