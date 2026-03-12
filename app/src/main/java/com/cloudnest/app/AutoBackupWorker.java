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
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Background Worker for Preset Folder Auto-Backup.
 * UPDATED: Implemented Recursive Sync to handle subfolders and system directories.
 * UPDATED: Added "Drive Full" detection and automatic account switching logic.
 */
public class AutoBackupWorker extends Worker {

    private static final String TAG = "AutoBackupWorker";
    private Drive driveService;
    private CloudNestDatabase db;
    private SequenceTrackerHelper tracker;
    private long currentPresetId;

    // Progress Tracking
    private int totalFilesToSync = 0;
    private int currentlySyncingIndex = 0;
    private long lastTime;
    private long lastBytes;
    private String currentSpeed = "0 KB/s";

    public AutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        db = CloudNestDatabase.getInstance(context);
        tracker = new SequenceTrackerHelper(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        String localFolderPath = getInputData().getString("FOLDER_PATH");
        String presetIdStr = getInputData().getString("PRESET_ID");

        if (localFolderPath == null || presetIdStr == null) return Result.failure();

        currentPresetId = Long.parseLong(presetIdStr);
        java.io.File localFolder = new java.io.File(localFolderPath);

        if (!localFolder.exists() || !localFolder.isDirectory()) {
            db.presetFolderDao().deleteById(currentPresetId);
            return Result.success();
        }

        try {
            authenticateDrive();
            if (driveService == null) return Result.failure();

            totalFilesToSync = 0;
            currentlySyncingIndex = 0;
            countFilesRecursive(localFolder);

            if (totalFilesToSync == 0) {
                db.presetFolderDao().updateSyncTime(currentPresetId, System.currentTimeMillis());
                return Result.success();
            }

            lastTime = System.currentTimeMillis();
            lastBytes = 0;
            
            String rootFolderId = findOrCreateFolder("CloudNest", "root");
            String targetRootId = findOrCreateFolder(localFolder.getName(), rootFolderId);
            syncFolderRecursive(localFolder, targetRootId);

            db.presetFolderDao().updateSyncTime(currentPresetId, System.currentTimeMillis());
            NotificationHelper.showUploadComplete(getApplicationContext(), totalFilesToSync);

            return Result.success();

        } catch (GoogleJsonResponseException e) {
            // --- GLITCH FIX: DETECT DRIVE FULL ---
            if (e.getStatusCode() == 403) {
                handleDriveFull();
                return Result.retry();
            }
            Log.e(TAG, "Auto-Backup failed: " + e.getMessage());
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Auto-Backup recursive sync failed: " + e.getMessage());
            NotificationHelper.showUploadFailed(getApplicationContext());
            return Result.retry();
        }
    }

    // --- GLITCH FIX: ACCOUNT SWITCHING LOGIC ---
    private void handleDriveFull() {
        GoogleSignInAccount currentAccount = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        if (currentAccount != null) {
            // 1. Mark current account as full in database
            DriveAccountEntity current = db.driveAccountDao().getAccountByEmail(currentAccount.getEmail());
            if (current != null) {
                current.isFull = true;
                current.isActive = false;
                db.driveAccountDao().update(current);
            }
            
            // 2. Find next available account
            DriveAccountEntity next = db.driveAccountDao().getNextAvailableAccount(currentAccount.getEmail());
            if (next != null) {
                db.driveAccountDao().setActive(next.email, true);
                Log.d(TAG, "Drive full. Switched to: " + next.email);
            } else {
                Log.e(TAG, "No more drives available!");
            }
        }
    }

    private void countFilesRecursive(java.io.File folder) {
        java.io.File[] files = folder.listFiles();
        if (files == null) return;

        for (java.io.File f : files) {
            if (f.isDirectory()) {
                countFilesRecursive(f);
            } else if (f.isFile() && f.length() > 0) {
                totalFilesToSync++;
            }
        }
    }

    private void syncFolderRecursive(java.io.File localFolder, String driveFolderId) throws IOException {
        if (isStopped()) return;

        Set<String> remoteFileNames = getRemoteFileNames(driveFolderId);
        java.io.File[] localItems = localFolder.listFiles();
        if (localItems == null) return;

        for (java.io.File item : localItems) {
            if (isStopped()) return;

            if (item.isDirectory()) {
                String subFolderId = findOrCreateFolder(item.getName(), driveFolderId);
                syncFolderRecursive(item, subFolderId);
            } else {
                if (!remoteFileNames.contains(item.getName())) {
                    if (item.canRead() && item.length() > 0) {
                        String driveId = uploadFileWithProgress(item, driveFolderId);
                        if (driveId != null) {
                            int seqNum = tracker.getNextSequenceNumber(currentPresetId);
                            tracker.trackFileUpload(item.getAbsolutePath(), seqNum, "user@gmail.com", driveId, currentPresetId, item.length());
                        }
                        currentlySyncingIndex++;
                    }
                } else {
                    currentlySyncingIndex++;
                }
            }
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

    private String uploadFileWithProgress(java.io.File localFile, String parentFolderId) throws IOException {
        File fileMeta = new File();
        fileMeta.setName(localFile.getName());
        fileMeta.setParents(Collections.singletonList(parentFolderId));

        FileContent mediaContent = new FileContent(null, localFile);

        Drive.Files.Create createRequest = driveService.files().create(fileMeta, mediaContent);

        MediaHttpUploader uploader = createRequest.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false); 
        uploader.setProgressListener(u -> {
            calculateSpeed(u.getNumBytesUploaded());
            updateWorkerProgress(localFile.getName(), u.getProgress());
        });

        return createRequest.setFields("id").execute().getId();
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
        if (overallPercent > 100) overallPercent = 100;
        
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