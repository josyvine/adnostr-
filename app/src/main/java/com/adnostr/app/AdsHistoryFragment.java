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
 * ADVERTISER MODE: Displays broadcasted ads; performs local deletion, 
 * generates Nostr Kind 5 Deletion events, AND sends HTTP DELETE requests to private cloud storage.
 * 
 * UPDATED: Replaced Blossom/NIP-96 media cleanup with Private Cloudflare R2 single-click wipes.
 * FIXED: Optimized deletion loop to ensure all associated media files are physically purged from R2.
 * RETAINED: Forensic console logging and multi-selection deletion logic.
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
        // 1. Get the list of items to delete
        List<String> selectedItems = adapter.getSelectedItems();

        // 2. CRITICAL SAFETY: If nothing is selected, hide button and exit
        if (selectedItems == null || selectedItems.isEmpty()) {
            adapter.clearSelection(); 
            return;
        }

        // 3. Perform Deletion based on Role
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            for (String ad : selectedItems) {
                db.deleteFromUserHistory(ad);
            }
            Toast.makeText(getContext(), "Selected ads removed locally.", Toast.LENGTH_SHORT).show();
        } else {
            // Logic for Advertisers: Local Deletion + Nostr Wipe + Private Cloudflare Physical Wipes
            broadcastNostrDeletionAndWipeMedia(selectedItems);
        }

        // 4. FIX: Clear selection immediately to hide the FAB and prevent double-clicks
        adapter.clearSelection();

        // 5. Refresh the list UI
        loadHistoryData();
    }

    /**
     * ADVERTISER ONLY: Implements NIP-09 (Event Deletion) and Private Cloudflare Cleanup.
     * This ensures the ad is hidden from the network and the files are physically wiped from R2.
     */
    private void broadcastNostrDeletionAndWipeMedia(List<String> adsToDelete) {
        try {
            // Using the Private CloudflareHelper for physical storage management
            CloudflareHelper cloudHelper = new CloudflareHelper();

            for (String fullPayload : adsToDelete) {
                JSONArray msgArray = new JSONArray(fullPayload);
                JSONObject adEvent = msgArray.getJSONObject(2);
                String adEventId = adEvent.getString("id");

                // --- PART 1: BROADCAST NOSTR KIND 5 ---
                JSONObject deletionEvent = new JSONObject();
                deletionEvent.put("kind", 5);
                deletionEvent.put("pubkey", db.getPublicKey());
                deletionEvent.put("created_at", System.currentTimeMillis() / 1000);
                deletionEvent.put("content", "Ad removed by advertiser.");

                JSONArray tags = new JSONArray();
                JSONArray eTag = new JSONArray();
                eTag.put("e");
                eTag.put(adEventId);
                tags.put(eTag);
                deletionEvent.put("tags", tags);

                JSONObject signedDeletion = NostrEventSigner.signEvent(db.getPrivateKey(), deletionEvent);

                if (signedDeletion != null) {
                    NostrPublisher.publishToPool(db.getRelayPool(), signedDeletion, (relayUrl, success, message) -> {
                        // Technical logs handled by the NostrPublisher's reporting hooks
                    });

                    // Cleanup the local database record
                    db.deleteFromAdvertiserHistory(fullPayload);
                }

                // --- PART 2: WIPE PRIVATE CLOUDFLARE R2 STORAGE ---
                // Retrieve the File IDs (Filenames) stored during the ad creation process
                String deletionIdsJson = db.getDeletionData(adEventId);
                if (deletionIdsJson != null && !deletionIdsJson.isEmpty()) {
                    JSONArray ids = new JSONArray(deletionIdsJson);
                    
                    // Iterate through all media associated with this ad
                    for (int i = 0; i < ids.length(); i++) {
                        String fileId = ids.getString(i);

                        // Execute the physical wipe from the Advertiser's private R2 bucket
                        cloudHelper.deleteMedia(requireContext(), fileId, new CloudflareHelper.CloudflareCallback() {
                            @Override 
                            public void onStatusUpdate(String log) { 
                                // Direct forensic reporting to the LogCat for deletion tracking
                                Log.d(TAG, "Private Cloud Wipe Status: " + log); 
                            }
                            
                            @Override public void onSuccess(String u, String d) {
                                Log.i(TAG, "Successfully wiped file: " + fileId);
                            }
                            
                            @Override 
                            public void onFailure(Exception e) { 
                                Log.e(TAG, "Cloud Deletion Failed for ID " + fileId + ": " + e.getMessage()); 
                            }
                        });
                    }
                    // Clean up the stored deletion mapping from the local database
                    db.removeDeletionData(adEventId);
                }
            }
            Toast.makeText(getContext(), "Ads hidden from network and storage space freed.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Bulk Deletion/Wipe Failure: " + e.getMessage());
            Toast.makeText(getContext(), "Error during wipe: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}