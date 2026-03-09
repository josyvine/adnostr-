package com.cloudnest.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.cloudnest.app.databinding.FragmentFileBrowserBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local File Browser (Phone & SD Card).
 * Handles directory navigation, file counting, and selection mode.
 * UPDATED: Fixed open-file crash, Glitch 5 (Folder Selection), and Glitch 7 (Presets).
 */
public class LocalBrowserFragment extends Fragment implements FileBrowserAdapter.OnFileItemClickListener {

    private FragmentFileBrowserBinding binding;
    private FileBrowserAdapter adapter;
    private File currentDirectory;
    private File rootDirectory;
    private boolean isGridMode = false;

    // Selection Mode
    private ActionMode actionMode;
    private final List<FileItemModel> selectedFiles = new ArrayList<>();
    
    // Database Executor
    private ExecutorService dbExecutor;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        dbExecutor = Executors.newSingleThreadExecutor();

        // Handle Back Button to navigate up folder hierarchy
        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentDirectory != null && !currentDirectory.equals(rootDirectory)) {
                    loadDirectory(currentDirectory.getParentFile());
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFileBrowserBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Determine if we are browsing Internal Phone storage or SD Card
        String storageType = getArguments() != null ? getArguments().getString("STORAGE_TYPE", "PHONE") : "PHONE";
        rootDirectory = getRootPath(storageType);
        currentDirectory = rootDirectory;

        setupRecyclerView();
        loadDirectory(currentDirectory);
    }

    private File getRootPath(String type) {
        if ("SD_CARD".equals(type)) {
            File[] fs = requireContext().getExternalFilesDirs(null);
            if (fs.length > 1 && fs[1] != null) {
                File sdRoot = fs[1];
                // Navigate up to find the physical root of the SD card
                while (sdRoot.getParentFile() != null && sdRoot.getParentFile().canRead()) {
                    File parent = sdRoot.getParentFile();
                    if (parent.getAbsolutePath().equals("/storage")) break;
                    sdRoot = parent;
                }
                return sdRoot;
            }
        }
        return Environment.getExternalStorageDirectory();
    }

    private void setupRecyclerView() {
        adapter = new FileBrowserAdapter(requireContext(), new ArrayList<>(), this);
        binding.recyclerViewFiles.setAdapter(adapter);
        setLayoutManager();
    }

    private void setLayoutManager() {
        if (isGridMode) {
            binding.recyclerViewFiles.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        } else {
            binding.recyclerViewFiles.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
    }

    /**
     * Lists files in the selected directory and updates UI.
     */
    private void loadDirectory(File directory) {
        if (directory == null || !directory.exists()) return;
        currentDirectory = directory;
        
        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(directory.getName());
            ((AppCompatActivity) getActivity()).getSupportActionBar().setSubtitle(directory.getAbsolutePath());
        }

        File[] files = directory.listFiles();
        List<FileItemModel> fileList = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                if (file.isHidden()) continue;

                int childCount = 0;
                if (file.isDirectory()) {
                    File[] subFiles = file.listFiles();
                    childCount = (subFiles != null) ? subFiles.length : 0;
                }

                fileList.add(new FileItemModel(
                        file.getName(),
                        file.getAbsolutePath(),
                        file.isDirectory(),
                        file.lastModified(),
                        file.length(),
                        childCount
                ));
            }
        }

        // Sort: Folders first, then alphabetically
        Collections.sort(fileList, (o1, o2) -> {
            if (o1.isDirectory() && !o2.isDirectory()) return -1;
            if (!o1.isDirectory() && o2.isDirectory()) return 1;
            return o1.getName().compareToIgnoreCase(o2.getName());
        });

        adapter.updateList(fileList);
        binding.tvEmptyFolder.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onFileClicked(FileItemModel fileModel) {
        if (actionMode != null) {
            toggleSelection(fileModel);
            return;
        }

        File file = new File(fileModel.getPath());
        if (file.isDirectory()) {
            loadDirectory(file);
        } else {
            openFile(file);
        }
    }

    @Override
    public void onFileLongClicked(FileItemModel fileModel) {
        if (actionMode == null) {
            actionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(actionModeCallback);
        }
        toggleSelection(fileModel);
    }

    private void toggleSelection(FileItemModel file) {
        adapter.toggleSelection(file);
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }

        if (selectedFiles.isEmpty()) {
            actionMode.finish();
        } else {
            actionMode.setTitle(selectedFiles.size() + " Selected");
        }
    }

    /**
     * FIXED CRASH: Uses FileProvider to securely open files with external apps.
     */
    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(), 
                    "com.cloudnest.app.fileprovider", file);

            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType == null) mimeType = "*/*";

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "No app found to open this file.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.browser_top_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_view) {
            isGridMode = !isGridMode;
            setLayoutManager();
            adapter.setGridMode(isGridMode);
            binding.recyclerViewFiles.setAdapter(adapter);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Contextual Menu for file selection (Upload, Delete, Share, Preset).
     */
    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.selection_action_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.action_upload) {
                confirmUpload(); // Fix Glitch 5
                return true;
            } else if (id == R.id.action_preset) {
                addToPresets(); // Fix Glitch 7
                return true;
            } else if (id == R.id.action_delete) {
                confirmDelete();
                return true;
            } else if (id == R.id.action_share) {
                shareSelectedFiles();
                return true;
            } else if (id == R.id.action_select_all) {
                adapter.selectAll();
                selectedFiles.clear();
                selectedFiles.addAll(adapter.getAllItems());
                mode.setTitle(selectedFiles.size() + " Selected");
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            adapter.clearSelection();
            selectedFiles.clear();
            actionMode = null;
        }
    };

    /**
     * Fix for Glitch 7: Adds only folders to the Auto-Backup queue.
     */
    private void addToPresets() {
        int folderCount = 0;
        CloudNestDatabase db = CloudNestDatabase.getInstance(requireContext());

        for (FileItemModel item : selectedFiles) {
            if (item.isDirectory()) {
                folderCount++;
                dbExecutor.execute(() -> {
                    PresetFolderEntity entity = new PresetFolderEntity();
                    entity.folderName = item.getName();
                    entity.localPath = item.getPath();
                    entity.lastSyncTime = 0;
                    db.presetFolderDao().insert(entity);
                });
            }
        }

        if (folderCount > 0) {
            Toast.makeText(requireContext(), folderCount + " folders added to Auto-Backup", Toast.LENGTH_SHORT).show();
            if (actionMode != null) actionMode.finish();
        } else {
            Toast.makeText(requireContext(), "Please select folders for Auto-Backup.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fix for Glitch 5: Shows Folder Selector before starting upload.
     */
    private void confirmUpload() {
        DriveFolderSelectorDialog dialog = DriveFolderSelectorDialog.newInstance((folderId, folderName) -> {
            // Once user selects a destination folder on Drive, start the Worker
            startUploadWorker(folderId, folderName);
        });
        dialog.show(getChildFragmentManager(), "DriveFolderSelector");
    }

    private void startUploadWorker(String driveFolderId, String driveFolderName) {
        String[] paths = new String[selectedFiles.size()];
        for (int i = 0; i < selectedFiles.size(); i++) {
            paths[i] = selectedFiles.get(i).getPath();
        }

        Data inputData = new Data.Builder()
                .putStringArray("FILE_PATHS", paths)
                .putString("DESTINATION_ID", driveFolderId)
                .build();

        OneTimeWorkRequest uploadRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(inputData)
                .addTag("MANUAL_UPLOAD")
                .build();

        WorkManager.getInstance(requireContext()).enqueue(uploadRequest);
        Toast.makeText(requireContext(), "Uploading to: " + driveFolderName, Toast.LENGTH_SHORT).show();
        if (actionMode != null) actionMode.finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Permanently?")
                .setMessage("Delete " + selectedFiles.size() + " items from device?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    for (FileItemModel item : selectedFiles) {
                        File file = new File(item.getPath());
                        deleteRecursive(file);
                    }
                    loadDirectory(currentDirectory);
                    if (actionMode != null) actionMode.finish();
                    Toast.makeText(requireContext(), "Deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void shareSelectedFiles() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (FileItemModel item : selectedFiles) {
            if (!item.isDirectory()) {
                File file = new File(item.getPath());
                Uri uri = FileProvider.getUriForFile(requireContext(), "com.cloudnest.app.fileprovider", file);
                uris.add(uri);
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), "Cannot share empty folders.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        shareIntent.setType("*/*");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
        if (actionMode != null) actionMode.finish();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dbExecutor != null) dbExecutor.shutdown();
    }
}