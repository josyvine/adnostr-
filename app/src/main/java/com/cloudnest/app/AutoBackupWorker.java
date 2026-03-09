package com.cloudnest.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Background Worker for Preset Folder Auto-Backup.
 * This worker intelligently syncs a local folder with a target folder in Google Drive.
 * UPDATED: Added speed calculation, metadata refresh for Drive counts, and notification support.
 */
public class AutoBackupWorker extends Worker {

    private static final String TAG = "AutoBackupWorker";
    private Drive driveService;
    private CloudNestDatabase db;

    // Progress Tracking
    private int totalFilesToSync = 0;
    private int currentlySyncingIndex = 0;
    private long lastTime;
    private long lastBytes;
    private String currentSpeed = "0 KB/s";

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        db = CloudNestDatabase.getInstance(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        String localFolderPath = getInputData().getString("FOLDER_PATH");
        String presetIdStr = getInputData().getString("PRESET_ID");
        
        if (localFolderPath == null || presetIdStr == null) return Result.failure();
        
        long presetId = Long.parseLong(presetIdStr);
        java.io.File localFolder = new java.io.File(localFolderPath);

        if (!localFolder.exists() || !localFolder.isDirectory()) {
            db.presetFolderDao().deleteById(presetId);
            return Result.success();
        }

        try {
            // 1. Authenticate with Google Drive
            authenticateDrive();
            if (driveService == null) return Result.failure();

            // 2. Resolve Root and Target Folders
            String rootFolderId = findOrCreateFolder("CloudNest", "root");
            String targetFolderId = findOrCreateFolder(localFolder.getName(), rootFolderId);

            // 3. Get list of files already in the Drive folder to avoid duplicates
            Set<String> remoteFileNames = getRemoteFileNames(targetFolderId);

            // 4. Identify new files
            java.io.File[] localFiles = localFolder.listFiles();
            if (localFiles == null) return Result.success();

            for (java.io.File f : localFiles) {
                if (f.isFile() && !remoteFileNames.contains(f.getName())) {
                    totalFilesToSync++;
                }
            }

            if (totalFilesToSync == 0) {
                db.presetFolderDao().updateSyncTime(presetId, System.currentTimeMillis());
                return Result.success();
            }

            // 5. Start Sync with Progress and Speed tracking
            lastTime = System.currentTimeMillis();
            
            for (java.io.File localFile : localFiles) {
                if (localFile.isFile() && !remoteFileNames.contains(localFile.getName())) {
                    uploadFileWithProgress(localFile, targetFolderId);
                    currentlySyncingIndex++;
                }
            }

            // 6. Update the 'lastSyncTime' (Fixes Glitch 6)
            db.presetFolderDao().updateSyncTime(presetId, System.currentTimeMillis());
            
            // Final Notification
            NotificationHelper.showUploadComplete(getApplicationContext(), totalFilesToSync);

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Auto-Backup sync failed: " + e.getMessage());
            return Result.retry();
        }
    }

    private void authenticateDrive() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        if (account == null) {
            driveService = null;
            return;
        }

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Collections.singleton(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account.getAccount());

        driveService = new Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                new GsonFactory(),
                credential)
                .setApplicationName("CloudNest Auto-Backup")
                .build();
    }

    private String findOrCreateFolder(String folderName, String parentId) throws IOException {
        String query = "mimeType = 'application/vnd.google-apps.folder' and " +
                       "name = '" + folderName + "' and '" + parentId + "' in parents and trashed = false";

        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .execute();

        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        } else {
            File folderMeta = new File();
            folderMeta.setName(folderName);
            folderMeta.setMimeType("application/vnd.google-apps.folder");
            folderMeta.setParents(Collections.singletonList(parentId));
            
            File createdFolder = driveService.files().create(folderMeta).setFields("id").execute();
            return createdFolder.getId();
        }
    }

    private Set<String> getRemoteFileNames(String folderId) throws IOException {
        Set<String> names = new HashSet<>();
        String query = "'" + folderId + "' in parents and trashed = false";
        String pageToken = null;
        do {
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(name)")
                    .setPageToken(pageToken)
                    .execute();
            if (result.getFiles() != null) {
                for (File file : result.getFiles()) {
                    names.add(file.getName());
                }
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);

        return names;
    }

    /**
     * Uploads a file and calculates real-time speed/progress.
     */
    private void uploadFileWithProgress(java.io.File localFile, String parentFolderId) throws IOException {
        File fileMeta = new File();
        fileMeta.setName(localFile.getName());
        fileMeta.setParents(Collections.singletonList(parentFolderId));

        FileContent mediaContent = new FileContent(null, localFile);

        Drive.Files.Create createRequest = driveService.files().create(fileMeta, mediaContent);
        
        MediaHttpUploader uploader = createRequest.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false); // Required for progress callbacks
        uploader.setProgressListener(u -> {
            calculateSpeed(u.getNumBytesUploaded());
            updateWorkerProgress(localFile.getName(), u.getProgress());
        });

        createRequest.setFields("id").execute();
    }

    private void calculateSpeed(long bytesUploaded) {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastTime;

        if (timeDiff >= 1000) {
            long bytesDiff = bytesUploaded - lastBytes;
            double speed = (bytesDiff / 1024.0) / (timeDiff / 1000.0);

            if (speed > 1024) {
                currentSpeed = String.format("%.2f MB/s", speed / 1024.0);
            } else {
                currentSpeed = String.format("%.2f KB/s", speed);
            }

            lastTime = currentTime;
            lastBytes = bytesUploaded;
        }
    }

    private void updateWorkerProgress(String fileName, double fileProgress) {
        int overallPercent = (int) (((currentlySyncingIndex + fileProgress) / (double) totalFilesToSync) * 100);
        String details = "Auto-sync: " + (currentlySyncingIndex + 1) + " of " + totalFilesToSync;

        Data progressData = new Data.Builder()
                .putString("CURRENT_FILE", fileName)
                .putInt("PROGRESS_PERCENT", overallPercent)
                .putString("SPEED", currentSpeed)
                .putString("DETAILS", details)
                .build();

        setProgressAsync(progressData);
        NotificationHelper.showUploadProgress(getApplicationContext(), overallPercent, details + " (" + currentSpeed + ")");
    }
}