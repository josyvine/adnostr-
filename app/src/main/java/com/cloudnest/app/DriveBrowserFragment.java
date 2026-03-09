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
import android.webkit.MimeTypeMap;
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
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google Drive File Browser.
 * Interacts directly with Drive API v3 to list, manage, and cache cloud files.
 * UPDATED: Fixed Glitch 1 (Thumbnails), Glitch 4 (In-app previewing), and stability.
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

    private static class FolderHistory {
        String id;
        String name;
        FolderHistory(String id, String name) { this.id = id; this.name = name; }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        networkExecutor = Executors.newSingleThreadExecutor();

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

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(requireContext());
        if (account != null) {
            driveService = DriveApiHelper.getDriveService(requireContext(), account);
            setupRecyclerView();
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

    private void loadDriveFolder(String folderId, String folderName, boolean addToStack) {
        if (addToStack) {
            navigationStack.push(new FolderHistory(currentFolderId, currentFolderName));
        }

        currentFolderId = folderId;
        currentFolderName = folderName;

        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(folderName);
        }

        binding.progressBar.setVisibility(View.VISIBLE);

        networkExecutor.execute(() -> {
            try {
                String query = "'" + folderId + "' in parents and trashed = false";
                
                // Fetch thumbnailLink for Glitch 1
                FileList result = driveService.files().list()
                        .setQ(query)
                        .setFields("files(id, name, mimeType, size, modifiedTime, thumbnailLink, webViewLink)")
                        .setOrderBy("folder, name")
                        .execute();

                List<File> driveFiles = result.getFiles();
                List<FileItemModel> uiList = new ArrayList<>();

                if (driveFiles != null) {
                    for (File file : driveFiles) {
                        boolean isDir = "application/vnd.google-apps.folder".equals(file.getMimeType());
                        uiList.add(new FileItemModel(
                                file.getId(),
                                file.getName(),
                                isDir,
                                file.getModifiedTime() != null ? file.getModifiedTime().getValue() : 0,
                                file.getSize() != null ? file.getSize() : 0,
                                file.getMimeType(),
                                file.getWebViewLink(),
                                file.getThumbnailLink(),
                                0
                        ));
                    }
                }

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        adapter.updateList(uiList);
                        binding.progressBar.setVisibility(View.GONE);
                        binding.tvEmptyFolder.setVisibility(uiList.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                }
            } catch (Exception e) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Failed to load files.", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    @Override
    public void onFileClicked(FileItemModel fileModel) {
        if (fileModel.isDirectory()) {
            loadDriveFolder(fileModel.getDriveId(), fileModel.getName(), true);
        } else {
            // Fix Glitch 4: Handle in-app preview instead of browser
            handleFilePreview(fileModel);
        }
    }

    /**
     * Fix Glitch 4: Downloads the file temporarily and opens it with an in-app viewer.
     */
    private void handleFilePreview(FileItemModel file) {
        ProgressDialog pd = new ProgressDialog(getContext());
        pd.setMessage("Preparing file...");
        pd.show();

        networkExecutor.execute(() -> {
            try {
                java.io.File cacheDir = new java.io.File(requireContext().getCacheDir(), "preview");
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
                        startActivity(Intent.createChooser(intent, "Open with"));
                    });
                }
            } catch (Exception e) {
                if (isAdded()) requireActivity().runOnUiThread(pd::dismiss);
            }
        });
    }

    @Override
    public void onFileLongClicked(FileItemModel fileModel) {
        showContextMenu(fileModel);
    }

    private void showContextMenu(FileItemModel file) {
        CharSequence[] options = {"Rename", "Delete", "Share Link"};
        new AlertDialog.Builder(requireContext())
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showRenameDialog(file);
                    else if (which == 1) confirmDelete(file);
                    else if (which == 2) shareLink(file);
                }).show();
    }

    private void showRenameDialog(FileItemModel file) {
        EditText input = new EditText(getContext());
        input.setText(file.getName());
        new AlertDialog.Builder(getContext())
                .setTitle("Rename").setView(input)
                .setPositiveButton("Rename", (d, w) -> performRename(file.getDriveId(), input.getText().toString()))
                .show();
    }

    private void performRename(String fileId, String newName) {
        networkExecutor.execute(() -> {
            try {
                File meta = new File(); meta.setName(newName);
                driveService.files().update(fileId, meta).execute();
                if (isAdded()) requireActivity().runOnUiThread(() -> loadDriveFolder(currentFolderId, currentFolderName, false));
            } catch (IOException e) { Log.e(TAG, "Rename error", e); }
        });
    }

    private void confirmDelete(FileItemModel file) {
        new AlertDialog.Builder(getContext())
                .setTitle("Delete?").setPositiveButton("Delete", (d, w) -> performDelete(file.getDriveId()))
                .show();
    }

    private void performDelete(String fileId) {
        networkExecutor.execute(() -> {
            try {
                File meta = new File(); meta.setTrashed(true);
                driveService.files().update(fileId, meta).execute();
                if (isAdded()) requireActivity().runOnUiThread(() -> loadDriveFolder(currentFolderId, currentFolderName, false));
            } catch (IOException e) { Log.e(TAG, "Delete error", e); }
        });
    }

    private void shareLink(FileItemModel file) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, file.getWebLink());
        startActivity(Intent.createChooser(i, "Share"));
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