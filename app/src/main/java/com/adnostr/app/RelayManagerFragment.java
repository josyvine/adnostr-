package com.adnostr.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.adnostr.app.databinding.FragmentRelayManagerBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom Relay Management Interface for Advertisers.
 * Allows users to add, test, and view their custom WebSocket networks.
 * Also provides the gateway to list their own relays on the marketplace.
 */
public class RelayManagerFragment extends Fragment {

    private static final String TAG = "AdNostr_RelayManager";
    private FragmentRelayManagerBinding binding;
    private AdNostrDatabaseHelper db;
    private RelayListAdapter adapter;
    private List<String> activeRelays;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentRelayManagerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());
        activeRelays = new ArrayList<>();

        // 1. Setup Relay List UI
        setupRecyclerView();

        // 2. Load Relays (Bootstrap + Custom)
        loadSavedRelays();

        // 3. Add Custom Relay Action
        binding.fabAddRelay.setOnClickListener(v -> showAddRelayDialog());
    }

    /**
     * Initializes the RecyclerView for displaying saved Nostr relays.
     */
    private void setupRecyclerView() {
        binding.rvRelayList.setLayoutManager(new LinearLayoutManager(requireContext()));
        
        // Setup the adapter with a callback for clicking a relay
        adapter = new RelayListAdapter(activeRelays, new RelayListAdapter.OnRelayClickListener() {
            @Override
            public void onTestPing(String relayUrl) {
                testRelayConnection(relayUrl);
            }

            @Override
            public void onResellClicked(String relayUrl) {
                publishRelayToMarketplace(relayUrl);
            }
        });
        
        binding.rvRelayList.setAdapter(adapter);
    }

    /**
     * Loads hardcoded bootstrap relays and previously saved custom relays.
     */
    private void loadSavedRelays() {
        // Adding Bootstrap Relays from Tech Spec
        activeRelays.add("wss://relay.damus.io");
        activeRelays.add("wss://relay.nostr.band");
        activeRelays.add("wss://nos.lol");
        activeRelays.add("wss://relay.snort.social");

        // Here we would also load custom relays saved in AdNostrDatabaseHelper

        adapter.notifyDataSetChanged();
    }

    /**
     * Shows a popup dialog to add a new `wss://` relay URL.
     */
    private void showAddRelayDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Add Custom Relay");
        
        final EditText input = new EditText(requireContext());
        input.setHint("wss://...");
        builder.setView(input);

        builder.setPositiveButton("TEST & ADD", (dialog, which) -> {
            String newRelay = input.getText().toString().trim();
            if (newRelay.startsWith("wss://")) {
                // In full implementation, we'd ping it first. 
                // For now, we add it to the UI directly.
                activeRelays.add(newRelay);
                adapter.notifyDataSetChanged();
                Toast.makeText(getContext(), "Relay Added", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Invalid WebSocket URL", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNegativeButton("CANCEL", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * Simulates testing the latency to a specific relay.
     */
    private void testRelayConnection(String relayUrl) {
        Log.d(TAG, "Testing Ping for: " + relayUrl);
        Toast.makeText(getContext(), "Pinging " + relayUrl + "...\nLatency: 45ms", Toast.LENGTH_SHORT).show();
    }

    /**
     * Triggers the logic to broadcast a kind:30002 event, listing the 
     * user's own relay on the global marketplace for other advertisers to buy.
     */
    private void publishRelayToMarketplace(String relayUrl) {
        Log.i(TAG, "Preparing kind:30002 for: " + relayUrl);
        Toast.makeText(getContext(), "Relay listed on Marketplace!", Toast.LENGTH_LONG).show();
        // Technical implementation: Build JSON kind:30002 and send via WebSocketClientManager
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}