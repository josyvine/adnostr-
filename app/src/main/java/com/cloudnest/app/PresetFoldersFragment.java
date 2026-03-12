package com.cloudnest.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.cloudnest.app.databinding.FragmentPresetFoldersBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-Backup Configuration Screen.
 * Lists all local folders marked as "Preset" for automatic syncing.
 * UPDATED: Fixed Glitch 6 (Sync status visibility) and Glitch 9 (Manual trigger logic).
 * UPDATED: Added menu inflation for Visual Cloud Ledger access.
 */
public class PresetFoldersFragment extends Fragment implements PresetFolderAdapter.OnPresetActionListener {

    private FragmentPresetFoldersBinding binding;
    private PresetFolderAdapter adapter;
    private CloudNestDatabase db;
    private ExecutorService dbExecutor;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // --- ENHANCEMENT: Enable options menu ---
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPresetFoldersBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize Database and background thread pool
        db = CloudNestDatabase.getInstance(requireContext());
        dbExecutor = Executors.newSingleThreadExecutor();

        setupRecyclerView();
        loadPresetFolders();
        setupAddButton();
    }

    // --- ENHANCEMENT: Add Menu Handling ---
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.preset_top_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_view_ledger) {
            Navigation.findNavController(requireView()).navigate(R.id.action_preset_to_ledger);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupRecyclerView() {
        if (binding == null) return;
        binding.recyclerViewPresets.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new PresetFolderAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewPresets.setAdapter(adapter);
    }

    /**
     * Fix for Glitch 6 & Navigation:
     * Provides clear instructions and navigates users to the storage browser 
     * where they can select a folder to add to the auto-backup queue.
     */
    private void setupAddButton() {
        if (binding == null) return;
        binding.fabAddPreset.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Setup Auto-Backup")
                    .setMessage("To add a folder to the backup list:\n\n1. Go to 'Phone Storage'\n2. Long-press the folder\n3. Tap the 'Add to Preset' icon (Star/Plus) in the top menu.")
                    .setPositiveButton("Go to Storage", (dialog, which) -> {
                        Bundle bundle = new Bundle();
                        bundle.putString("STORAGE_TYPE", "PHONE");
                        Navigation.findNavController(v).navigate(R.id.nav_local_browser, bundle);
                    })
                    .setNegativeButton("Maybe Later", null)
                    .show();
        });
    }

    /**
     * Loads the list of configured folders from Room Database.
     * Observes LiveData for real-time updates to fix the "Never Synced" glitch.
     */
    private void loadPresetFolders() {
        db.presetFolderDao().getAllPresets().observe(getViewLifecycleOwner(), presetFolders -> {
            if (binding == null) return;

            if (presetFolders == null || presetFolders.isEmpty()) {
                binding.tvEmptyPresets.setVisibility(View.VISIBLE);
                binding.recyclerViewPresets.setVisibility(View.GONE);
            } else {
                binding.tvEmptyPresets.setVisibility(View.GONE);
                binding.recyclerViewPresets.setVisibility(View.VISIBLE);
                adapter.updateList(presetFolders);
            }
        });
    }

    // --- Interaction Listener Implementation ---

    /**
     * Triggers a manual sync of a specific preset folder.
     * Fixes Glitch 9 by ensuring the worker is enqueued with correct parameters.
     */
    @Override
    public void onSyncNowClicked(PresetFolderEntity folder) {
        Data inputData = new Data.Builder()
                .putString("FOLDER_PATH", folder.localPath)
                .putString("PRESET_ID", String.valueOf(folder.id))
                .build();

        // Enqueue the worker specifically for this folder
        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(AutoBackupWorker.class)
                .setInputData(inputData)
                .addTag("AUTO_BACKUP")
                .build();

        WorkManager.getInstance(requireContext()).enqueue(syncRequest);
        
        Toast.makeText(requireContext(), "Sync started for: " + folder.folderName, Toast.LENGTH_SHORT).show();
    }

    /**
     * Removes a folder from the auto-backup system.
     */
    @Override
    public void onRemoveClicked(PresetFolderEntity folder) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Stop Auto-Backup?")
                .setMessage("CloudNest will stop monitoring '" + folder.folderName + "'. Files already on Drive will remain safe.")
                .setPositiveButton("Stop Syncing", (dialog, which) -> {
                    dbExecutor.execute(() -> {
                        db.presetFolderDao().delete(folder);
                        
                        // Notify user on UI thread
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> 
                                Toast.makeText(requireContext(), "Folder removed from presets.", Toast.LENGTH_SHORT).show()
                            );
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Navigation helper to view the cloud destination.
     */
    @Override
    public void onViewInDriveClicked(PresetFolderEntity folder) {
        // Redirect to the Drive Browser at the root CloudNest folder
        Navigation.findNavController(requireView()).navigate(R.id.nav_drive_browser);
        Toast.makeText(requireContext(), "Opening CloudNest folder...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Prevent crashes by shutting down executor and clearing binding
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
        }
        binding = null;
    }
}