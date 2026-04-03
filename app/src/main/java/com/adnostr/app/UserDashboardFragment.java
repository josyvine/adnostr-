package com.adnostr.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.adnostr.app.databinding.FragmentUserDashboardBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dashboard for standard AdNostr Users.
 * UI for selecting ad interests (hashtags) and monitoring decentralized relay connectivity.
 */
public class UserDashboardFragment extends Fragment implements HashtagAdapter.OnHashtagClickListener {

    private static final String TAG = "AdNostr_UserDash";
    private FragmentUserDashboardBinding binding;
    private AdNostrDatabaseHelper db;
    private HashtagAdapter adapter;
    private List<String> availableHashtags;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the user dashboard layout
        binding = FragmentUserDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());

        // 1. Setup the Interest Selection UI
        setupHashtagGrid();

        // 2. Refresh the real-time relay connection status
        refreshRelayStatus();

        // 3. Setup Listeners
        binding.btnEditInterests.setOnClickListener(v -> {
            // Logic to toggle between edit/view mode if needed
            Log.d(TAG, "User requested to update interest hashtags.");
        });
    }

    /**
     * Initializes the grid of hashtags using a custom adapter.
     */
    private void setupHashtagGrid() {
        // Sample bootstrap hashtags based on technical specification
        availableHashtags = new ArrayList<>();
        availableHashtags.add("food");
        availableHashtags.add("kochi");
        availableHashtags.add("electronics");
        availableHashtags.add("realestate");
        availableHashtags.add("cars");
        availableHashtags.add("fashion");
        availableHashtags.add("deals");

        // Load the user's currently saved interests from the database
        Set<String> savedInterests = db.getInterests();

        // Initialize the HashtagAdapter with the data and selection logic
        adapter = new HashtagAdapter(availableHashtags, savedInterests, this);
        
        // Use a 3-column grid for the hashtag chips
        binding.rvHashtags.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.rvHashtags.setAdapter(adapter);
    }

    /**
     * Updates the connection indicator at the top of the dashboard.
     * In a live app, this reflects the number of active WebSockets.
     */
    private void refreshRelayStatus() {
        // For the UI demonstration, we pull a count. 
        // In the full implementation, this comes from the WebSocketClientManager.
        int relayCount = 34; // Placeholder for connected relays
        
        binding.tvRelayStatus.setText("Connected to " + relayCount + " Relays");
        binding.ivStatusDot.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light));
        
        Log.i(TAG, "Dashboard updated: Relays active.");
    }

    /**
     * Implementation of HashtagAdapter.OnHashtagClickListener.
     * Triggered when a user toggles a hashtag chip.
     */
    @Override
    public void onHashtagToggled(String hashtag, boolean isSelected) {
        try {
            Set<String> currentInterests = new HashSet<>(db.getInterests());

            if (isSelected) {
                currentInterests.add(hashtag);
                Log.d(TAG, "Interest added: #" + hashtag);
            } else {
                currentInterests.remove(hashtag);
                Log.d(TAG, "Interest removed: #" + hashtag);
            }

            // Save the updated list to local storage
            db.saveInterests(currentInterests);

            // Important: Background listener (WorkManager) uses these tags 
            // for Nostr Relay filters to push matching ads.
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to update interests: " + e.getMessage());
            throw new RuntimeException("Hashtag Selection Persistence Failure: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}