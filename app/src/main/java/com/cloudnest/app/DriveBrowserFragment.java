package com.cloudnest.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cloudnest.app.databinding.FragmentFileBrowserBinding;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Drive File Browser.
 * Interacts directly with Drive API v3 to list, manage, and cache cloud files.
 * Supports:
 * - Navigation (Root -> Subfolders)
 * - Creating Folders
 * - Deleting/Renaming Files
 * - "Make Available Offline" (Caching)
 * UPDATED: Fixed Glitch 1 (Thumbnails), Glitch 4 (In-app previewing), and Life-cycle Crashes.
 */
public class DriveBrowserFragment extends Fragment implements FileBrowserAdapter.OnFileItemClickListener {

    private static final String TAG = "DriveBrowserFragment";
    private FragmentFileBrowserBinding binding;
    private FileBrowserAdapter adapter;
    private Drive driveService;
    private ExecutorService networkExecutor;

    // Navigation State
    private String currentFolderId = "root";
    private String currentFolderName = "My Drive";
    private final Stack<FolderHistory> navigationStack = new Stack<>();
    private boolean isGridMode = false;

    // Helper class to track history for Back Button navigation
    private static class FolderHistory {
        String id;
        String name;

        FolderHistory(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true); // Enable Create Folder / Sort options

        networkExecutor = Executors.newSingleThreadExecutor();

        // Handle Back Button to navigate up the Drive folder tree
        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!navigationStack.isEmpty()) {
                    FolderHistory previous = navigationStack.pop();
                    loadDriveFolder(previous.id, previous.name, false);
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

        // 1. Initialize Drive Service
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
        if (account != null) {
            GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                    requireContext(), Collections.singleton(DriveScopes.DRIVE_FILE));
            credential.setSelectedAccount(account.getAccount());

            driveService = new Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    new GsonFactory(),
                    credential)
                    .setApplicationName(getString(R.string.app_name))
                    .build();

            // 2. Setup UI
            setupRecyclerView();

            // 3. Load Root Folder
            loadDriveFolder("root", "My Drive", false);
        } else {
            Toast.makeText(getContext(), "Not signed in to Google Drive", Toast.LENGTH_SHORT).show();
        }
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
     * Fetches files from the Drive API.
     * @param folderId The Drive ID of the folder to list.
     * @param folderName Display name for the toolbar.
     * @param addToStack Whether to push the *previous* folder to the history stack.
     */
    private void loadDriveFolder(String folderId, String folderName, boolean addToStack) {
        if (addToStack) {
            navigationStack.push(new FolderHistory(currentFolderId, currentFolderName));
        }

        currentFolderId = folderId;
        currentFolderName = folderName;

        // Update Toolbar
        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(folderName);
            ((AppCompatActivity) getActivity()).getSupportActionBar().setSubtitle("Google Drive");
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvEmptyFolder.setVisibility(View.GONE);

        networkExecutor.execute(() -> {
            try {
                // Query: "parents in 'folderId' and trashed = false"
                String query = "'" + folderId + "' in parents and trashed = false";
                
                // FIXED GLITCH 1: Added thumbnailLink to fields
                FileList result = driveService.files().list()
                        .setQ(query)
                        .setFields("files(id, name, mimeType, size, modifiedTime, iconLink, webViewLink, thumbnailLink)")
                        .setOrderBy("folder, name")
                        .execute();

                List<File> driveFiles = result.getFiles();
                List<FileItemModel> uiList = new ArrayList<>();

                if (driveFiles != null) {
                    for (File file : driveFiles) {
                        boolean isDir = "application/vnd.google-apps.folder".equals(file.getMimeType());
                        long size = file.getSize() != null ? file.getSize() : 0;
                        long time = file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0;

                        // Preserve your logic but pass the thumbnailLink to the model
                        FileItemModel model = new FileItemModel(
                                file.getId(), 
                                file.getName(),
                                isDir,
                                time,
                                size,
                                file.getMimeType(),
                                file.getWebViewLink()
                        );
                        model.setThumbnailUrl(file.getThumbnailLink());
                        uiList.add(model);
                    }
                }

                // Sort: Folders first
                Collections.sort(uiList, (o1, o2) -> {
                    if (o1.isDirectory() && !o2.isDirectory()) return -1;
                    if (!o1.isDirectory() && o2.isDirectory()) return 1;
                    return o1.getName().compareToIgnoreCase(o2.getName());
                });

                // FIXED CRASH: Check if fragment is still added before UI updates
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            adapter.updateList(uiList);
                            binding.progressBar.setVisibility(View.GONE);
                            if (uiList.isEmpty()) {
                                binding.tvEmptyFolder.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Drive List Error: " + e.getMessage());
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Failed to load Drive files.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    // --- Interaction Interfaces ---

    @Override
    public void onFileClicked(FileItemModel fileModel) {
        if (fileModel.isDirectory()) {
            loadDriveFolder(fileModel.getDriveId(), fileModel.getName(), true);
        } else {
            // FIXED GLITCH 4: Use In-App Preview logic instead of Browser Redirect
            handleFilePreview(fileModel);
        }
    }

    /**
     * FIXED GLITCH 4: Downloads file to cache and opens it in-app using FileProvider
     */
    private void handleFilePreview(FileItemModel file) {
        ProgressDialog pd = new ProgressDialog(getContext());
        pd.setMessage("Opening file...");
        pd.setCancelable(false);
        pd.show();

        networkExecutor.execute(() -> {
            try {
                java.io.File cacheDir = new java.io.File(requireContext().getCacheDir(), "preview_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                java.io.File localFile = new java.io.File(cacheDir, file.getName());
                
                OutputStream os = new FileOutputStream(localFile);
                driveService.files().get(file.getDriveId()).executeMediaAndDownloadTo(os);
                os.close();

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        pd.dismiss();
                        Uri uri = FileProvider.getUriForFile(requireContext(), 
                                "com.cloudnest.app.fileprovider", localFile);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, file.getMimeType());
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Open file with"));
                    });
                }
            } catch (Exception e) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        pd.dismiss();
                        Toast.makeText(getContext(), "Could not open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    @Override
    public void onFileLongClicked(FileItemModel fileModel) {
        showContextMenu(fileModel);
    }

    private void showContextMenu(FileItemModel file) {
        CharSequence[] options = {"Make Available Offline", "Rename", "Delete", "Share Link"};

        new AlertDialog.Builder(requireContext())
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            downloadForOffline(file);
                            break;
                        case 1:
                            showRenameDialog(file);
                            break;
                        case 2:
                            confirmDelete(file);
                            break;
                        case 3:
                            shareLink(file);
                            break;
                    }
                })
                .show();
    }

    // --- Drive Operations ---

    /**
     * Downloads file content to local app cache folder.
     */
    private void downloadForOffline(FileItemModel file) {
        if (file.isDirectory()) {
            Toast.makeText(getContext(), "Cannot cache entire folders yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(getContext());
        progressDialog.setMessage("Making available offline...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        networkExecutor.execute(() -> {
            try {
                // Define local cache path
                java.io.File cacheDir = new java.io.File(requireContext().getExternalFilesDir(null), "OfflineCache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                
                java.io.File localFile = new java.io.File(cacheDir, file.getName());
                OutputStream outputStream = new FileOutputStream(localFile);

                driveService.files().get(file.getDriveId()).executeMediaAndDownloadTo(outputStream);
                outputStream.flush();
                outputStream.close();

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(getContext(), "File saved to Offline Cache.", Toast.LENGTH_SHORT).show();
                    });
                }

            } catch (IOException e) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(getContext(), "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void showRenameDialog(FileItemModel file) {
        EditText input = new EditText(getContext());
        input.setText(file.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(getContext())
                .setTitle("Rename File")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String newName = input.getText().toString();
                    if (!newName.isEmpty()) {
                        performRename(file.getDriveId(), newName);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performRename(String fileId, String newName) {
        networkExecutor.execute(() -> {
            try {
                File metadata = new File();
                metadata.setName(newName);
                driveService.files().update(fileId, metadata).execute();
                
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Renamed successfully", Toast.LENGTH_SHORT).show();
                        loadDriveFolder(currentFolderId, currentFolderName, false); // Refresh
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Rename Error: " + e.getMessage());
            }
        });
    }

    private void confirmDelete(FileItemModel file) {
        new AlertDialog.Builder(getContext())
                .setTitle("Delete from Drive?")
                .setMessage("Are you sure you want to move '" + file.getName() + "' to trash?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    performDelete(file.getDriveId());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performDelete(String fileId) {
        networkExecutor.execute(() -> {
            try {
                File metadata = new File();
                metadata.setTrashed(true);
                driveService.files().update(fileId, metadata).execute();
                
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Moved to Trash", Toast.LENGTH_SHORT).show();
                        loadDriveFolder(currentFolderId, currentFolderName, false); // Refresh
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Delete Error: " + e.getMessage());
            }
        });
    }

    private void shareLink(FileItemModel file) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Shared via CloudNest");
        shareIntent.putExtra(Intent.EXTRA_TEXT, file.getWebLink());
        startActivity(Intent.createChooser(shareIntent, "Share Link"));
    }

    private void showCreateFolderDialog() {
        EditText input = new EditText(getContext());
        input.setHint("Folder Name");

        new AlertDialog.Builder(getContext())
                .setTitle("Create New Folder")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString();
                    if (!name.isEmpty()) {
                        createFolder(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createFolder(String folderName) {
        networkExecutor.execute(() -> {
            try {
                File fileMetadata = new File();
                fileMetadata.setName(folderName);
                fileMetadata.setMimeType("application/vnd.google-apps.folder");
                fileMetadata.setParents(Collections.singletonList(currentFolderId));

                driveService.files().create(fileMetadata)
                        .setFields("id")
                        .execute();

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Folder created", Toast.LENGTH_SHORT).show();
                        loadDriveFolder(currentFolderId, currentFolderName, false); // Refresh
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Create Folder Error: " + e.getMessage());
            }
        });
    }

    // --- Options Menu ---

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        // Inflate a menu that adds "Create Folder" and "Toggle View"
        inflater.inflate(R.menu.browser_top_menu, menu);
        
        // Add specific Drive options if needed
        menu.add(Menu.NONE, 100, Menu.NONE, "Create New Folder");
        
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_view) {
            isGridMode = !isGridMode;
            setLayoutManager();
            adapter.setGridMode(isGridMode);
            return true;
        } else if (item.getItemId() == 100) { // Create Folder ID
            showCreateFolderDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (networkExecutor != null) networkExecutor.shutdown();
        binding = null;
    }
}