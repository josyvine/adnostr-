package com.adnostr.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewpager2.widget.ViewPager2;

import com.adnostr.app.databinding.DialogCloudflareConfigBinding;
import com.adnostr.app.databinding.DialogIdentityBackupBinding;
import com.adnostr.app.databinding.DialogModeSwitchBinding;
import com.adnostr.app.databinding.DialogPrivacySettingsBinding;
import com.adnostr.app.databinding.DialogUsernameSetupBinding;
import com.adnostr.app.databinding.FragmentSettingsBinding;

/**
 * Global Settings Interface for AdNostr.
 * UPDATED: Transformed into a modern Icon-driven Command Center.
 * RETAINED: 100% of original Username Manager, Cloudflare, and Role Switching logic.
 * FEATURE: Functions now launch in professionally rendered square icon popups.
 * ENHANCEMENT: Added My Hashtags deed registry for advertisers.
 * ENHANCEMENT: Added JSON Identity Portability (Backup & Restore).
 * ENHANCEMENT: Added Privacy Command Center for anonymity and location controls (Feature 1).
 */
public class SettingsFragment extends Fragment implements SettingsIconAdapter.OnSettingClickListener {

    private static final String TAG = "AdNostr_Settings";
    private FragmentSettingsBinding binding;
    private AdNostrDatabaseHelper db;
    private SettingsIconAdapter adapter;

    // ENHANCEMENT: Launchers for the Android Storage Access Framework (SAF)
    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register the launcher for EXPORTING the JSON backup
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        if (result.getData().getData() != null) {
                            BackupManager.exportProfileToJson(requireContext(), result.getData().getData());
                        }
                    }
                }
        );

        // Register the launcher for IMPORTING the JSON backup
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        if (result.getData().getData() != null) {
                            BackupManager.importProfileFromJson(requireContext(), result.getData().getData());
                        }
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the main settings layout
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = AdNostrDatabaseHelper.getInstance(requireContext());

        if (binding == null) return;

        // 1. Display Current Identity Header (Top of the screen)
        setupIdentityDisplay();

        // 2. Initialize the Command Center Icon Grid
        setupIconGrid();
    }

    /**
     * Initializes the RecyclerView with square Instagram-style icons.
     * Icons are filtered automatically based on the user's role.
     */
    private void setupIconGrid() {
        String role = db.getUserRole();
        binding.tvCurrentModeLabel.setText("Active Mode: " + role);
        
        // 3-Column professionally spaced grid
        binding.rvSettingsIcons.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        adapter = new SettingsIconAdapter(role, this);
        binding.rvSettingsIcons.setAdapter(adapter);
    }

    /**
     * INTERFACE: Handles clicks on the square grid icons.
     * Routes each command to its respective original logic portal.
     */
    @Override
    public void onSettingClicked(int commandType) {
        switch (commandType) {
            case SettingsIconAdapter.CMD_PROFILE:
                showUsernameDialog();
                break;
            case SettingsIconAdapter.CMD_MODE_SWITCH:
                showModeSwitchDialog();
                break;
            case SettingsIconAdapter.CMD_PRIVACY:
                showPrivacyDialog();
                break;
            case SettingsIconAdapter.CMD_CLOUDFLARE:
                showCloudflareDialog();
                break;
            case SettingsIconAdapter.CMD_MY_HASHTAGS:
                showMyHashtagsDialog();
                break;
            case SettingsIconAdapter.CMD_HISTORY:
                navigateToHistory();
                break;
            case SettingsIconAdapter.CMD_BACKUP:
                showIdentityBackupDialog();
                break;
            case SettingsIconAdapter.CMD_RESET:
                showResetConfirmation();
                break;
        }
    }

    /**
     * FEATURE 1: Privacy Command Center Dialog.
     * Manages toggles for Username Anonymity and Live Location broadcasting.
     */
    private void showPrivacyDialog() {
        DialogPrivacySettingsBinding dialogBinding = DialogPrivacySettingsBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setView(dialogBinding.getRoot())
                .create();

        // 1. Load current states from Database
        dialogBinding.switchUsernameDiscovery.setChecked(db.isUsernameHidden());
        dialogBinding.switchLiveLocation.setChecked(db.isLiveLocationEnabled());

        // 2. Handle Username Visibility Toggle
        dialogBinding.switchUsernameDiscovery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setUsernameHidden(isChecked);
            String msg = isChecked ? "Username will be hidden from broadcasts." : "Username visibility restored.";
            Log.d(TAG, "Privacy: Username Hidden = " + isChecked);
        });

        // 3. Handle Live Location Toggle (Beacon Mode)
        dialogBinding.switchLiveLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setLiveLocationEnabled(isChecked);
            
            // Logic for Feature 3: Service control placeholder
            // In Feature 3, we will add the startService / stopService logic here
            if (isChecked) {
                Log.d(TAG, "Privacy: Requesting to start Location Beacon Service.");
            } else {
                Log.d(TAG, "Privacy: Disabling Location Beacon.");
            }
        });

        dialogBinding.btnCancelPrivacy.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * ENHANCEMENT COMMAND POPUP: JSON Identity Portability.
     * Allows the user to download or restore their identity passport.
     */
    private void showIdentityBackupDialog() {
        DialogIdentityBackupBinding dialogBinding = DialogIdentityBackupBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setView(dialogBinding.getRoot())
                .create();

        // Trigger the Android System File Creator
        dialogBinding.btnDownloadPassport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "adnostr_backup.json");
            exportLauncher.launch(intent);
            dialog.dismiss();
        });

        // Trigger the Android System File Picker
        dialogBinding.btnRestorePassport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            importLauncher.launch(intent);
            dialog.dismiss();
        });

        dialogBinding.btnCloseBackup.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * COMMAND POPUP: Moving the original Username Logic into a Dialog.
     * Handles network checking, claiming, and releasing via Kind 0 events.
     */
    private void showUsernameDialog() {
        DialogUsernameSetupBinding dialogBinding = DialogUsernameSetupBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setView(dialogBinding.getRoot())
                .create();

        String savedName = db.getUsername();

        if (savedName == null || savedName.trim().isEmpty()) {
            // MODE: READY TO CLAIM
            dialogBinding.etUsernamePopup.setText("");
            dialogBinding.etUsernamePopup.setEnabled(true);
            dialogBinding.btnUsernameActionPopup.setText("CLAIM USERNAME");
            dialogBinding.btnUsernameActionPopup.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3")));

            dialogBinding.btnUsernameActionPopup.setOnClickListener(v -> {
                String requestedName = dialogBinding.etUsernamePopup.getText().toString().trim();
                if (requestedName.isEmpty()) {
                    Toast.makeText(getContext(), "Please enter a name.", Toast.LENGTH_SHORT).show();
                    return;
                }

                dialogBinding.btnUsernameActionPopup.setEnabled(false);
                dialogBinding.btnUsernameActionPopup.setText("CHECKING...");

                UsernameManager.checkAvailability(requestedName, db.getPublicKey(), (isAvailable, message) -> {
                    if (!isAdded()) return;
                    if (isAvailable) {
                        UsernameManager.claimUsername(requireContext(), requestedName, db.getPrivateKey(), db.getPublicKey(), (success, claimMsg) -> {
                            if (success) {
                                db.saveUsername(requestedName);
                                Toast.makeText(getContext(), "Username Locked!", Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            } else {
                                dialogBinding.btnUsernameActionPopup.setEnabled(true);
                                dialogBinding.btnUsernameActionPopup.setText("CLAIM FAILED");
                            }
                        });
                    } else {
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                        dialogBinding.btnUsernameActionPopup.setEnabled(true);
                        dialogBinding.btnUsernameActionPopup.setText("CLAIM USERNAME");
                    }
                });
            });
        } else {
            // MODE: ALREADY OWNED, ALLOW RELEASE
            dialogBinding.etUsernamePopup.setText(savedName);
            dialogBinding.etUsernamePopup.setEnabled(false);
            dialogBinding.btnUsernameActionPopup.setText("RELEASE USERNAME");
            dialogBinding.btnUsernameActionPopup.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF5252")));

            dialogBinding.btnUsernameActionPopup.setOnClickListener(v -> {
                dialogBinding.btnUsernameActionPopup.setEnabled(false);
                dialogBinding.btnUsernameActionPopup.setText("RELEASING...");
                
                UsernameManager.releaseUsername(requireContext(), db.getPrivateKey(), db.getPublicKey(), (success, releaseMsg) -> {
                    if (success) {
                        db.saveUsername("");
                        Toast.makeText(getContext(), "Username Released.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        dialogBinding.btnUsernameActionPopup.setEnabled(true);
                    }
                });
            });
        }

        dialogBinding.btnCancelUsername.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * COMMAND POPUP: Moving the original Cloudflare Logic into a Dialog.
     * Manages Advertiser Private Storage credentials.
     */
    private void showCloudflareDialog() {
        DialogCloudflareConfigBinding dialogBinding = DialogCloudflareConfigBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setView(dialogBinding.getRoot())
                .create();

        // Load existing credentials
        dialogBinding.etCloudflareUrlPopup.setText(db.getCloudflareWorkerUrl());
        dialogBinding.etCloudflareTokenPopup.setText(db.getCloudflareSecretToken());

        dialogBinding.btnSaveCloudflarePopup.setOnClickListener(v -> {
            String url = dialogBinding.etCloudflareUrlPopup.getText().toString().trim();
            String token = dialogBinding.etCloudflareTokenPopup.getText().toString().trim();

            if (url.startsWith("http")) {
                db.saveCloudflareWorkerUrl(url);
                db.saveCloudflareSecretToken(token);
                Toast.makeText(getContext(), "Cloudflare Configuration Saved", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Private Storage Updated: " + url);
                dialog.dismiss();
            } else {
                Toast.makeText(getContext(), "Invalid Worker URL", Toast.LENGTH_SHORT).show();
            }
        });

        dialogBinding.btnCancelCloudflare.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * COMMAND POPUP: Professional Role Switcher.
     * Allows instant migration between User and Advertiser profiles.
     */
    private void showModeSwitchDialog() {
        DialogModeSwitchBinding dialogBinding = DialogModeSwitchBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setView(dialogBinding.getRoot())
                .create();

        String currentRole = db.getUserRole();
        boolean isUser = RoleSelectionActivity.ROLE_USER.equals(currentRole);

        dialogBinding.tvCurrentModePopup.setText("Currently: " + currentRole);
        dialogBinding.ivCurrentModeIcon.setImageResource(isUser ? android.R.drawable.ic_menu_myplaces : android.R.drawable.ic_menu_sort_by_size);
        dialogBinding.btnSwitchActionPopup.setText(isUser ? "ACTIVATE ADVERTISER MODE" : "ACTIVATE USER MODE");

        dialogBinding.btnSwitchActionPopup.setOnClickListener(v -> {
            String newRole = isUser ? RoleSelectionActivity.ROLE_ADVERTISER : RoleSelectionActivity.ROLE_USER;
            db.saveUserRole(newRole);
            
            Toast.makeText(getContext(), "Role switched to " + newRole, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            
            // Trigger global UI refresh to update the Command Center icons
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshRoleAndUI();
            }
        });

        dialogBinding.btnCancelMode.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * NEW COMMAND: Launches the My Hashtags Registry Manager.
     */
    private void showMyHashtagsDialog() {
        // Registry logic to be implemented in the specialized registry manager
        // This launches the popup where advertisers manage their deeds.
        HashtagRegistryManager.showRegistryDialog(requireContext(), getChildFragmentManager());
    }

    /**
     * Icon Action: Quick jump to the History ViewPager index.
     */
    private void navigateToHistory() {
        if (getActivity() != null) {
            ViewPager2 pager = getActivity().findViewById(R.id.mainViewPager);
            if (pager != null) pager.setCurrentItem(1, true);
        }
    }

    /**
     * Displays the truncated hex identity at the top of the Command Center.
     */
    private void setupIdentityDisplay() {
        if (binding == null) return;
        String pubKey = db.getPublicKey();
        if (pubKey != null) {
            String truncated = pubKey.substring(0, 10) + "..." + pubKey.substring(pubKey.length() - 6);
            binding.tvIdentityKey.setText(truncated);
        }
    }

    /**
     * Final confirmation before wiping the decentralized identity and keys.
     */
    private void showResetConfirmation() {
        new AlertDialog.Builder(requireContext(), R.style.Theme_AdNostr_Dialog)
                .setTitle("Clear All Data?")
                .setMessage("This will permanently delete your Nostr keys and settings. You will need to start onboarding again.")
                .setPositiveButton("RESET", (dialog, which) -> {
                    db.clearAllData();
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
        binding = null; // Prevent memory leaks
    }
}