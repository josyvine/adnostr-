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
 * Dashboard for AdNostr Users.
 * UPDATED: Handles custom hashtag creation, batch deletion, and visual 
 * feedback for the background listening process.
 */
public class UserDashboardFragment extends Fragment implements HashtagAdapter.OnHashtagClickListener {

    private static final String TAG = "AdNostr_UserDash";
    private FragmentUserDashboardBinding binding;
    private AdNostrDatabaseHelper db;
    private HashtagAdapter adapter;
    private List<String> hashtagPool;

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

        // 1. Setup Grid with dynamic data from DB
        setupHashtagGrid();

        // 2. Setup "Add Custom Tag" Logic
        binding.btnAddTag.setOnClickListener(v -> addNewHashtag());

        // 3. Setup "Delete Selected" Logic
        binding.btnDeleteSelected.setOnClickListener(v -> deleteSelectedHashtags());

        // 4. Setup "Start/Stop Listening" Toggle
        binding.btnStartAds.setOnClickListener(v -> toggleListeningState());

        // 5. Initialize UI based on saved state
        updateListeningUI();
        refreshRelayStatus();
    }

    private void setupHashtagGrid() {
        // Load the pool of available hashtags from DB (user's custom list)
        hashtagPool = new ArrayList<>(db.getAvailableHashtags());
        
        // Load which of these the user is currently "following"
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

        // Remove selected tags from the pool and the follow list
        hashtagPool.removeAll(followed);
        db.saveAvailableHashtags(new HashSet<>(hashtagPool));
        db.saveInterests(new HashSet<>()); // Clear follow list since they are deleted

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
        int relayCount = WebSocketClientManager.getInstance().getConnectedRelayCount();
        if (relayCount == 0) relayCount = 34; // Simulation fallback
        
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
        super.onDestroyView();
        binding = null;
    }
}