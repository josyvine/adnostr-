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
 * UPDATED: Fixed navigation logic for adding new preset folders.
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
        adapter = new PresetFolderAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewPresets.setAdapter(adapter);
    }

    /**
     * UPDATED: Implementation for Glitch 6.
     * Directs the user to the storage browser to pick a folder.
     */
    private void setupAddButton() {
        binding.fabAddPreset.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Add Auto-Backup Folder")
                    .setMessage("To add a folder, go to 'Phone Storage', long-press the folder you want, and select the 'Add to Preset' icon in the top menu.")
                    .setPositiveButton("Go to Storage", (dialog, which) -> {
                        // FIX: Actual navigation logic added here
                        Bundle bundle = new Bundle();
                        bundle.putString("STORAGE_TYPE", "PHONE");
                        Navigation.findNavController(v).navigate(R.id.nav_local_browser, bundle);
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
                .setMessage("Stop syncing '" + folder.folderName + "'? Existing files on Drive will remain.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    dbExecutor.execute(() -> {
                        db.presetFolderDao().delete(folder);
                        
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> 
                                Toast.makeText(requireContext(), "Folder removed from sync list.", Toast.LENGTH_SHORT).show()
                            );
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onViewInDriveClicked(PresetFolderEntity folder) {
        // Logic to open drive browser at specific folder location if needed
        Toast.makeText(requireContext(), "Opening Drive Location...", Toast.LENGTH_SHORT).show();
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