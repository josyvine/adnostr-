package com.cloudnest.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.cloudnest.app.databinding.FragmentPresetFoldersBinding;
import com.cloudnest.app.AutoBackupWorker; // To be generated
import com.cloudnest.app.PresetFolderAdapter; // To be generated
import com.cloudnest.app.PresetFolderEntity; // To be generated

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-Backup Configuration Screen.
 * Lists all local folders marked as "Preset" for automatic syncing.
 * Allows users to:
 * - View sync status and last sync time.
 * - Manually trigger "Sync Now".
 * - Remove folders from the auto-backup list.
 */
public class PresetFoldersFragment extends Fragment implements PresetFolderAdapter.OnPresetActionListener {

    private FragmentPresetFoldersBinding binding;
    private PresetFolderAdapter adapter;
    private CloudNestDatabase db;
    private ExecutorService dbExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPresetFoldersBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = CloudNestDatabase.getInstance(requireContext());
        dbExecutor = Executors.newSingleThreadExecutor();

        setupRecyclerView();
        loadPresetFolders();
        setupAddButton();
    }

    private void setupRecyclerView() {
        binding.recyclerViewPresets.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Initialize adapter with empty list and 'this' as listener
        adapter = new PresetFolderAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewPresets.setAdapter(adapter);
    }

    private void setupAddButton() {
        binding.fabAddPreset.setOnClickListener(v -> {
            // Direct user to Local Browser to pick a folder
            // We use a custom intent action or flag to tell LocalBrowserFragment
            // to return a result instead of just opening the folder.
            // For simplicity in this flow, we show instructions.
            new AlertDialog.Builder(requireContext())
                    .setTitle("Add Auto-Backup Folder")
                    .setMessage("To add a folder, go to 'Phone Storage' or 'SD Card', long press a folder, and select 'Add to Preset/Auto Backup'.")
                    .setPositiveButton("Go to Storage", (dialog, which) -> {
                        // Navigate to Local Browser
                        // (Navigation logic handled by MainActivity/NavGraph)
                        // In a real implementation, you'd navigate via ID.
                        Toast.makeText(requireContext(), "Opening File Browser...", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    /**
     * Loads the list of configured folders from Room Database.
     * Observes LiveData for real-time updates.
     */
    private void loadPresetFolders() {
        db.presetFolderDao().getAllPresets().observe(getViewLifecycleOwner(), new Observer<List<PresetFolderEntity>>() {
            @Override
            public void onChanged(List<PresetFolderEntity> presetFolders) {
                if (presetFolders == null || presetFolders.isEmpty()) {
                    binding.tvEmptyPresets.setVisibility(View.VISIBLE);
                    binding.recyclerViewPresets.setVisibility(View.GONE);
                } else {
                    binding.tvEmptyPresets.setVisibility(View.GONE);
                    binding.recyclerViewPresets.setVisibility(View.VISIBLE);
                    adapter.updateList(presetFolders);
                }
            }
        });
    }

    // --- Interaction Listener Implementation ---

    @Override
    public void onSyncNowClicked(PresetFolderEntity folder) {
        // Trigger immediate OneTimeWorkRequest for this specific folder
        Data inputData = new Data.Builder()
                .putString("FOLDER_PATH", folder.localPath)
                .putString("PRESET_ID", String.valueOf(folder.id))
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(AutoBackupWorker.class)
                .setInputData(inputData)
                .addTag("AUTO_BACKUP")
                .build();

        WorkManager.getInstance(requireContext()).enqueue(syncRequest);
        Toast.makeText(requireContext(), "Sync started for: " + folder.folderName, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRemoveClicked(PresetFolderEntity folder) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove Auto-Backup?")
                .setMessage("Stop syncing '" + folder.folderName + "'? Files already uploaded to Drive will NOT be deleted.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    dbExecutor.execute(() -> {
                        db.presetFolderDao().delete(folder);
                        
                        requireActivity().runOnUiThread(() -> 
                            Toast.makeText(requireContext(), "Folder removed from Auto-Backup.", Toast.LENGTH_SHORT).show()
                        );
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onViewInDriveClicked(PresetFolderEntity folder) {
        // Open Drive Browser to the specific destination folder ID
        // Logic handled by Navigation Graph args, here we simplify.
        Toast.makeText(requireContext(), "Opening Drive Folder: " + folder.driveFolderId, Toast.LENGTH_SHORT).show();
        // In real app: Navigate(R.id.driveBrowser, bundle with folderId)
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
        }
        binding = null;
    }
}