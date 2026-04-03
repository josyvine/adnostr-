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
import androidx.navigation.Navigation;

import com.adnostr.app.databinding.FragmentAdvDashboardBinding;

/**
 * Dashboard for AdNostr Advertisers.
 * Displays business metrics, identity status, and provide the entry point 
 * for creating and broadcasting new decentralized ads.
 */
public class AdvDashboardFragment extends Fragment {

    private static final String TAG = "AdNostr_AdvDash";
    private FragmentAdvDashboardBinding binding;
    private AdNostrDatabaseHelper db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the advertiser dashboard layout
        binding = FragmentAdvDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());

        // 1. Setup Business Identity Header
        setupIdentityHeader();

        // 2. Refresh Metrics (Active Ads, Reach, etc.)
        refreshBusinessMetrics();

        // 3. Setup Primary Floating Action Button (FAB)
        // Navigates to the CreateAdFragment as per the technical spec
        binding.fabCreateAd.setOnClickListener(v -> {
            Log.d(TAG, "Advertiser requested to create a new ad broadcast.");
            Navigation.findNavController(v).navigate(R.id.nav_create_ad);
        });

        // 4. Setup shortcut to Relay Management
        binding.btnManageRelays.setOnClickListener(v -> {
            Navigation.findNavController(v).navigate(R.id.nav_relay_marketplace);
        });
    }

    /**
     * Displays the Advertiser's public identity.
     * Uses the hex public key generated during the Splash phase.
     */
    private void setupIdentityHeader() {
        String pubKey = db.getPublicKey();
        
        if (pubKey != null && !pubKey.isEmpty()) {
            // Truncate for display (e.g., npub1xyz...89q)
            String displayId = "ID: " + pubKey.substring(0, 8) + "..." + pubKey.substring(pubKey.length() - 4);
            binding.tvAdvertiserId.setText(displayId);
        }

        // Mock status check: PRO vs FREE
        // In full implementation, this checks the local verified license
        boolean isPro = false; 
        
        if (isPro) {
            binding.tvStatusBadge.setText("PRO SUBSCRIBER");
            binding.tvStatusBadge.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.holo_orange_dark));
        } else {
            binding.tvStatusBadge.setText("FREE USER");
            binding.tvStatusBadge.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.darker_gray));
        }
    }

    /**
     * Updates the performance cards.
     * These metrics are calculated by counting local kind:30001 event history 
     * and active WebSocket relay connections.
     */
    private void refreshBusinessMetrics() {
        try {
            // Metrics Logic (Mocked for UI visualization)
            int activeAds = 4;
            int relayReach = 45;

            binding.tvActiveAdsCount.setText(String.valueOf(activeAds));
            binding.tvRelayReachCount.setText(String.valueOf(relayReach));
            
            Log.i(TAG, "Advertiser metrics refreshed successfully.");

        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh advertiser metrics: " + e.getMessage());
            throw new RuntimeException("Metrics Refresh Failure: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}