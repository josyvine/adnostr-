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

import java.io.IOException;
import java.util.Collections;

/**
 * Background Upload Worker.
 * Handles recursive file scanning and uploading to Google Drive.
 * UPDATED: Added real-time speed calculation, precise progress tracking, 
 * destination folder support, and OS Notifications.
 */
public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker";
    private Drive driveService;
    private int totalFiles = 0;
    private int uploadedFiles = 0;

    // Speed Tracking
    private long startTime;
    private long lastTime;
    private long lastBytes;
    private String currentSpeed = "0 KB/s";

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String[] filePaths = getInputData().getStringArray("FILE_PATHS");
        String destinationId = getInputData().getString("DESTINATION_ID");

        if (filePaths == null) return Result.failure();
        if (destinationId == null || destinationId.isEmpty()) destinationId = "root";

        // 1. Authenticate
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        if (account == null) return Result.failure();

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Collections.singleton(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account.getAccount());

        driveService = new Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                new GsonFactory(),
                credential)
                .setApplicationName("CloudNest")
                .build();

        // 2. Initial Setup
        startTime = System.currentTimeMillis();
        lastTime = startTime;

        try {
            // Count files for progress bar
            for (String path : filePaths) {
                countFilesRecursive(new java.io.File(path));
            }

            NotificationHelper.showUploadProgress(getApplicationContext(), 0, "Starting upload...");

            // 3. Process Uploads
            for (String path : filePaths) {
                processAndUpload(new java.io.File(path), destinationId);
            }

            NotificationHelper.showUploadComplete(getApplicationContext(), totalFiles);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Upload Error: " + e.getMessage());
            NotificationHelper.showUploadFailed(getApplicationContext());
            return Result.retry();
        }
    }

    private void countFilesRecursive(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] files = file.listFiles();
            if (files != null) {
                for (java.io.File f : files) countFilesRecursive(f);
            }
        } else {
            totalFiles++;
        }
    }

    private void processAndUpload(java.io.File localFile, String parentFolderId) throws IOException {
        if (localFile.isDirectory()) {
            // Check if folder exists or create it
            String folderId = DriveApiHelper.findFolderId(driveService, localFile.getName(), parentFolderId);
            if (folderId == null) {
                File folderMeta = new File();
                folderMeta.setName(localFile.getName());
                folderMeta.setMimeType("application/vnd.google-apps.folder");
                folderMeta.setParents(Collections.singletonList(parentFolderId));
                folderId = driveService.files().create(folderMeta).setFields("id").execute().getId();
            }

            java.io.File[] children = localFile.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    processAndUpload(child, folderId);
                }
            }
        } else {
            uploadSingleFile(localFile, parentFolderId);
        }
    }

    private void uploadSingleFile(java.io.File localFile, String parentFolderId) throws IOException {
        File fileMeta = new File();
        fileMeta.setName(localFile.getName());
        fileMeta.setParents(Collections.singletonList(parentFolderId));

        FileContent mediaContent = new FileContent(null, localFile);

        Drive.Files.Create createRequest = driveService.files().create(fileMeta, mediaContent);
        
        // Setup Progress Listener for Speed and Percentage
        MediaHttpUploader uploader = createRequest.getMediaHttpUploader();
        uploader.setDirectUploadEnabled(false); // Enable chunked upload for progress tracking
        uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
            @Override
            public void progressChanged(MediaHttpUploader uploader) throws IOException {
                calculateSpeed(uploader.getNumBytesUploaded());
                updateUIProgress(localFile.getName(), uploader.getProgress());
            }
        });

        createRequest.setFields("id").execute();
        uploadedFiles++;
    }

    private void calculateSpeed(long bytesUploaded) {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastTime;

        if (timeDiff >= 1000) { // Update speed every second
            long bytesDiff = bytesUploaded - lastBytes;
            double speed = (bytesDiff / 1024.0) / (timeDiff / 1000.0); // KB/s

            if (speed > 1024) {
                currentSpeed = String.format("%.2f MB/s", speed / 1024.0);
            } else {
                currentSpeed = String.format("%.2f KB/s", speed);
            }

            lastTime = currentTime;
            lastBytes = bytesUploaded;
        }
    }

    private void updateUIProgress(String fileName, double fileProgress) {
        // Calculate overall percentage
        int overallPercent = (int) (((uploadedFiles + fileProgress) / (double) totalFiles) * 100);
        String details = "File " + (uploadedFiles + 1) + " of " + totalFiles;

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