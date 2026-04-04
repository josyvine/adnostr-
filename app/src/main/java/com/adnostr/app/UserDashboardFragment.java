package com.adnostr.app;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dashboard for standard AdNostr Users.
 * UPDATED: Restored 31+ relay connectivity by implementing the network pool 
 * and adding live status listeners for the UI.
 */
public class UserDashboardFragment extends Fragment implements HashtagAdapter.OnHashtagClickListener {

    private static final String TAG = "AdNostr_UserDash";
    private FragmentUserDashboardBinding binding;
    private AdNostrDatabaseHelper db;
    private HashtagAdapter adapter;
    private List<String> hashtagPool;
    private WebSocketClientManager wsManager;

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

        // 1. Setup Grid with dynamic data from DB
        setupHashtagGrid();

        // 2. Setup "Add Custom Tag" Logic
        binding.btnAddTag.setOnClickListener(v -> addNewHashtag());

        // 3. Setup "Delete Selected" Logic
        binding.btnDeleteSelected.setOnClickListener(v -> deleteSelectedHashtags());

        // 4. Setup "Start/Stop Listening" Toggle
        binding.btnStartAds.setOnClickListener(v -> toggleListeningState());

        // 5. NEW: Initialize the Full Relay Pool
        // This ensures the app connects to all 31+ bootstrap nodes defined in the DB helper
        wsManager.connectPool(db.getRelayPool());

        // 6. NEW: Setup Live Status Listener
        // Updates the "Connected to X Relays" text dynamically as nodes come online
        setupNetworkStatusListener();

        // Initial UI refresh
        updateListeningUI();
        refreshRelayStatus();
    }

    private void setupNetworkStatusListener() {
        wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
            @Override
            public void onRelayConnected(String url) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> refreshRelayStatus());
                }
            }

            @Override
            public void onRelayDisconnected(String url, String reason) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> refreshRelayStatus());
                }
            }

            @Override
            public void onMessageReceived(String url, String message) {
                // Background listener handles ad messages
            }

            @Override
            public void onError(String url, Exception ex) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> refreshRelayStatus());
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
            Toast.makeText(getContext(), "Hashtag added to your grid", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Hashtag already exists", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(getContext(), "Selected hashtags removed", Toast.LENGTH_SHORT).show();
    }

    private void toggleListeningState() {
        boolean currentState = db.isListening();
        boolean newState = !currentState;

        db.setListeningState(newState);
        updateListeningUI();

        if (newState) {
            Toast.makeText(getContext(), "Ad monitoring activated!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Ad monitoring paused.", Toast.LENGTH_SHORT).show();
        }
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
        if (!db.getInterests().isEmpty()) {
            binding.btnDeleteSelected.setVisibility(View.VISIBLE);
        } else {
            binding.btnDeleteSelected.setVisibility(View.GONE);
        }
    }

    private void refreshRelayStatus() {
        if (binding == null) return;
        int relayCount = wsManager.getConnectedRelayCount();
        binding.tvRelayStatus.setText("Connected to " + relayCount + " Relays");
    }

    @Override
    public void onHashtagToggled(String hashtag, boolean isSelected) {
        Set<String> currentInterests = new HashSet<>(db.getInterests());
        if (isSelected) {
            currentInterests.add(hashtag);
        } else {
            currentInterests.remove(hashtag);
        }
        db.saveInterests(currentInterests);
        updateDeleteButtonVisibility();
    }

    @Override
    public void onDestroyView() {
        // Clear listener to prevent memory leaks
        wsManager.setStatusListener(null);
        super.onDestroyView();
        binding = null;
    }
}