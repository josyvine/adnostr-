package com.adnostr.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
 * FIXED: Username section is now hidden for Advertisers.
 * NEW: Implements Decentralized Username checks, claiming, and releasing via UsernameManager.
 * NEW ENHANCEMENT: Added Custom Media Relay (Blossom/NIP-96) configuration.
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

        // Safety check to ensure binding is available
        if (binding == null) return;

        // 1. Display Current Identity
        setupIdentityDisplay();

        // 2. Setup Role Switching Logic
        setupRoleToggle();

        // 3. Setup Data Management
        binding.btnResetApp.setOnClickListener(v -> showResetConfirmation());

        // 4. Setup Username Logic (Exclusive to USER role)
        configureProfileSectionVisibility();

        // 5. NEW: Setup Media Relay (Blossom/NIP-96) settings
        setupMediaRelaySettings();
    }

    /**
     * Hides the profile/username section if the user is an Advertiser.
     * Initializes the decentralized logic if they are a User.
     */
    private void configureProfileSectionVisibility() {
        if (binding == null) return;

        String currentRole = db.getUserRole();
        
        if (RoleSelectionActivity.ROLE_ADVERTISER.equals(currentRole)) {
            // Hide Username box entirely for Advertisers
            binding.tvProfileHeader.setVisibility(View.GONE);
            binding.cvProfileCard.setVisibility(View.GONE);
        } else {
            // Show and configure for Users
            binding.tvProfileHeader.setVisibility(View.VISIBLE);
            binding.cvProfileCard.setVisibility(View.VISIBLE);
            refreshUsernameUIState();
        }
    }

    /**
     * Toggles the Username UI between "CLAIM" mode and "RELEASE" mode 
     * based on whether they currently own a name in the local database.
     */
    private void refreshUsernameUIState() {
        if (binding == null) return;

        String savedName = db.getUsername();

        if (savedName == null || savedName.trim().isEmpty()) {
            // MODE: READY TO CLAIM A NEW USERNAME
            binding.etUsername.setText("");
            binding.etUsername.setEnabled(true);
            binding.btnSaveUsername.setText("CLAIM USERNAME");
            binding.btnSaveUsername.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3"))); // Blue

            binding.btnSaveUsername.setOnClickListener(v -> {
                String requestedName = binding.etUsername.getText().toString().trim();
                if (requestedName.isEmpty()) {
                    Toast.makeText(getContext(), "Please enter a username.", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Prevent spam clicks
                binding.btnSaveUsername.setEnabled(false);
                binding.btnSaveUsername.setText("CHECKING NETWORK...");
                binding.etUsername.setEnabled(false);

                // 1. Check network availability
                UsernameManager.checkAvailability(requestedName, db.getPublicKey(), (isAvailable, message) -> {
                    if (!isAdded() || binding == null) return;

                    if (isAvailable) {
                        binding.btnSaveUsername.setText("CLAIMING ON BLOCKCHAIN...");
                        
                        // 2. Broadcast Kind 0 to claim it globally
                        UsernameManager.claimUsername(requireContext(), requestedName, db.getPrivateKey(), db.getPublicKey(), (success, claimMsg) -> {
                            if (!isAdded() || binding == null) return;

                            if (success) {
                                db.saveUsername(requestedName);
                                Toast.makeText(getContext(), "Username Locked & Claimed!", Toast.LENGTH_SHORT).show();
                                refreshUsernameUIState(); // Refresh to DELETE mode
                            } else {
                                Toast.makeText(getContext(), "Claim failed: " + claimMsg, Toast.LENGTH_LONG).show();
                                resetToClaimState();
                            }
                        });
                    } else {
                        Toast.makeText(getContext(), "ERROR: " + message, Toast.LENGTH_LONG).show();
                        resetToClaimState();
                    }
                });
            });

        } else {
            // MODE: ALREADY OWNS A USERNAME, ALLOW DELETION/RELEASE
            binding.etUsername.setText(savedName);
            binding.etUsername.setEnabled(false); // Lock input
            binding.btnSaveUsername.setText("RELEASE USERNAME");
            binding.btnSaveUsername.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF5252"))); // Red
            binding.btnSaveUsername.setEnabled(true);

            binding.btnSaveUsername.setOnClickListener(v -> {
                // Prevent spam clicks
                binding.btnSaveUsername.setEnabled(false);
                binding.btnSaveUsername.setText("RELEASING TO NETWORK...");

                // Broadcast empty Kind 0 to wipe metadata from relays
                UsernameManager.releaseUsername(requireContext(), db.getPrivateKey(), db.getPublicKey(), (success, releaseMsg) -> {
                    if (!isAdded() || binding == null) return;

                    if (success) {
                        db.saveUsername(""); // Wipe locally
                        Toast.makeText(getContext(), "Username Released and Deleted.", Toast.LENGTH_SHORT).show();
                        refreshUsernameUIState(); // Refresh to CLAIM mode
                    } else {
                        Toast.makeText(getContext(), "Release failed: " + releaseMsg, Toast.LENGTH_LONG).show();
                        refreshUsernameUIState(); // Reset failure
                    }
                });
            });
        }
    }

    /**
     * NEW: Configures the Media Relay (Blossom) settings section.
     * Allows Advertisers/Users to set a custom upload/download server.
     */
    private void setupMediaRelaySettings() {
        if (binding == null) return;

        // Retrieve current saved server from database
        String savedServer = db.getCustomMediaServer();
        
        // If your XML layout has etMediaServer and btnSaveMediaServer
        if (binding.etMediaServer != null) {
            binding.etMediaServer.setText(savedServer);
        }

        if (binding.btnSaveMediaServer != null) {
            binding.btnSaveMediaServer.setOnClickListener(v -> {
                String newServer = binding.etMediaServer.getText().toString().trim();
                
                // Basic URL validation
                if (!newServer.isEmpty() && !newServer.startsWith("http")) {
                    Toast.makeText(getContext(), "Invalid URL. Must start with http/https", Toast.LENGTH_SHORT).show();
                    return;
                }

                db.saveCustomMediaServer(newServer);
                Toast.makeText(getContext(), "Media Relay Configuration Saved", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Custom Blossom Server updated to: " + newServer);
            });
        }
    }

    /**
     * Helper to revert the UI if a claim fails.
     */
    private void resetToClaimState() {
        if (binding != null) {
            binding.btnSaveUsername.setEnabled(true);
            binding.btnSaveUsername.setText("CLAIM USERNAME");
            binding.etUsername.setEnabled(true);
        }
    }

    /**
     * Displays the user's public identity as a truncated hex string.
     */
    private void setupIdentityDisplay() {
        if (binding == null) return;

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
        if (binding == null) return;

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
            if (binding == null) return;

            String newRole = RoleSelectionActivity.ROLE_USER.equals(currentRole) 
                    ? RoleSelectionActivity.ROLE_ADVERTISER 
                    : RoleSelectionActivity.ROLE_USER;

            // Save the new role to the database
            db.saveUserRole(newRole);

            Toast.makeText(getContext(), "Role switched to " + newRole, Toast.LENGTH_SHORT).show();

            // Refresh the MainActivity UI to update the ViewPager and TabLayout
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshRoleAndUI();
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
        binding = null; // Clean up binding to prevent memory leaks
    }
}