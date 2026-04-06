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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.adnostr.app.databinding.FragmentSettingsBinding;

/**
 * Global Settings Interface for AdNostr.
 * Handles identity display, role switching between User and Advertiser,
 * and local database management.
 * FIXED: Added logic to load and save optional Username for Reach Discovery.
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "AdNostr_Settings";
    private FragmentSettingsBinding binding;
    private AdNostrDatabaseHelper db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the settings layout
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());

        // NEW: Load existing username from database and display it
        binding.etUsername.setText(db.getUsername());

        // NEW: Save username button click listener
        binding.btnSaveUsername.setOnClickListener(v -> {
            String name = binding.etUsername.getText().toString().trim();
            db.saveUsername(name);
            Toast.makeText(getContext(), "Username Saved!", Toast.LENGTH_SHORT).show();
        });

        // 1. Display Current Identity
        setupIdentityDisplay();

        // 2. Setup Role Switching Logic
        setupRoleToggle();

        // 3. Setup Data Management
        binding.btnResetApp.setOnClickListener(v -> showResetConfirmation());
    }

    /**
     * Displays the user's public identity as a truncated hex string.
     */
    private void setupIdentityDisplay() {
        String pubKey = db.getPublicKey();
        if (pubKey != null) {
            String truncated = pubKey.substring(0, 10) + "..." + pubKey.substring(pubKey.length() - 6);
            binding.tvIdentityKey.setText(truncated);
        }

        // Display current active mode
        String currentRole = db.getUserRole();
        binding.tvCurrentMode.setText("Active Mode: " + currentRole);
    }

    /**
     * Configures the toggle to switch between User and Advertiser profiles instantly.
     */
    private void setupRoleToggle() {
        String currentRole = db.getUserRole();
        
        // Update button text based on current role
        if (RoleSelectionActivity.ROLE_USER.equals(currentRole)) {
            binding.btnSwitchRole.setText("SWITCH TO ADVERTISER MODE");
            binding.btnSwitchRole.setIconResource(android.R.drawable.ic_menu_sort_by_size);
        } else {
            binding.btnSwitchRole.setText("SWITCH TO USER MODE");
            binding.btnSwitchRole.setIconResource(android.R.drawable.ic_menu_myplaces);
        }

        binding.btnSwitchRole.setOnClickListener(v -> {
            String newRole = RoleSelectionActivity.ROLE_USER.equals(currentRole) 
                    ? RoleSelectionActivity.ROLE_ADVERTISER 
                    : RoleSelectionActivity.ROLE_USER;

            // Save the new role to the database
            db.saveUserRole(newRole);
            
            Toast.makeText(getContext(), "Role switched to " + newRole, Toast.LENGTH_SHORT).show();

            // Refresh the MainActivity UI to update the bottom navigation and dashboards
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshRoleAndUI();
                
                // Navigate to the new dashboard for the selected role
                setupRoleToggle(); // Refresh button text locally
                setupIdentityDisplay(); // Refresh identity text
            }
        });
    }

    /**
     * Confirmation dialog before wiping all Nostr keys and settings.
     */
    private void showResetConfirmation() {
        new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setTitle("Clear All Data?")
                .setMessage("This will permanently delete your Nostr keys, saved relays, and interests. You will need to start onboarding again.")
                .setPositiveButton("RESET", (dialog, which) -> {
                    db.clearAllData();
                    
                    // Restart the app from the Splash screen
                    Intent intent = new Intent(requireContext(), SplashActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    requireActivity().finish();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}