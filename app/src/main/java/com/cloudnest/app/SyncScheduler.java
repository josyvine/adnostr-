package com.cloudnest.app;

import android.content.Context;
import android.util.Log;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Utility class to manage background scheduling for Preset Folders.
 * This ensures that folders are scanned for new files automatically.
 * UPDATED: Fixed Tag Mismatch for Upload Manager visibility and improved Enqueue Policy.
 */
public class SyncScheduler {

    private static final String TAG = "SyncScheduler";
    private static final String TAG_PREFIX = "SYNC_JOB_";

    /**
     * Schedules a periodic background sync (every 1 hour) for a specific preset folder.
     */
    public static void scheduleFolderSync(Context context, PresetFolderEntity folder) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data inputData = new Data.Builder()
                .putString("FOLDER_PATH", folder.localPath)
                .putString("PRESET_ID", String.valueOf(folder.id))
                .build();

        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                AutoBackupWorker.class, 
                1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("AUTO_BACKUP")
                .addTag(TAG_PREFIX + folder.id)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TAG_PREFIX + folder.id,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
        );
        
        Log.d(TAG, "Scheduled periodic sync for: " + folder.folderName);
    }

    /**
     * NEW: Triggers an IMMEDIATE sync for a folder.
     * Logic for Glitch 1: Called by FolderWatcherService when a new file is detected.
     * UPDATED: Changed tag to AUTO_BACKUP for UI visibility and policy to APPEND_OR_REPLACE.
     */
    public static void triggerImmediateSync(Context context, PresetFolderEntity folder) {
        Data inputData = new Data.Builder()
                .putString("FOLDER_PATH", folder.localPath)
                .putString("PRESET_ID", String.valueOf(folder.id))
                .build();

        OneTimeWorkRequest instantRequest = new OneTimeWorkRequest.Builder(AutoBackupWorker.class)
                .setInputData(inputData)
                .addTag("AUTO_BACKUP") // Changed from AUTO_BACKUP_INSTANT to match UI observer
                .build();

        // APPEND_OR_REPLACE ensures that new file detections don't cancel an active upload
        WorkManager.getInstance(context).enqueueUniqueWork(
                "INSTANT_" + folder.id,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                instantRequest
        );
        
        Log.d(TAG, "Triggered instant sync for: " + folder.folderName);
    }

    /**
     * Cancels the background schedule for a specific folder.
     */
    public static void stopFolderSync(Context context, long folderId) {
        WorkManager.getInstance(context).cancelUniqueWork(TAG_PREFIX + folderId);
        WorkManager.getInstance(context).cancelUniqueWork("INSTANT_" + folderId);
    }

    /**
     * FIXED LOGIC: Loops through all presets in the database and ensures they are scheduled.
     * Logic for Glitch 1: Called in MainActivity to start the system.
     */
    public static void refreshAllSchedules(Context context) {
        CloudNestDatabase db = CloudNestDatabase.getInstance(context);
        
        // Use a background thread to access Room database
        new Thread(() -> {
            try {
                // Get all presets from the DAO
                List<PresetFolderEntity> presets = db.presetFolderDao().getAllPresetsSync();
                
                if (presets != null) {
                    for (PresetFolderEntity folder : presets) {
                        scheduleFolderSync(context, folder);
                    }
                }
                
                // FIXED FOR GLITCH 1: Also start the Instant Watcher Service
                FolderWatcherService.startService(context);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to refresh sync schedules: " + e.getMessage());
            }
        }).start();
    }
}