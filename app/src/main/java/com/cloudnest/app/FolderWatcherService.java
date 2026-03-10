package com.cloudnest.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.FileObserver;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground Service that monitors Preset Folders in real-time.
 * Uses FileObserver to detect new files and trigger instant sync.
 * FIXES: Glitch 1 (Automatic detection of new files).
 */
public class FolderWatcherService extends Service {

    private static final String TAG = "FolderWatcherService";
    private static final String CHANNEL_ID = "cloudnest_watcher_channel";
    private static final int NOTIFICATION_ID = 5001;

    private final List<CustomFileObserver> observers = new ArrayList<>();
    private CloudNestDatabase db;

    /**
     * Static helper to start the service safely.
     */
    public static void startService(Context context) {
        Intent intent = new Intent(context, FolderWatcherService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Static helper to stop the service.
     */
    public static void stopService(Context context) {
        Intent intent = new Intent(context, FolderWatcherService.class);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = CloudNestDatabase.getInstance(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as Foreground to prevent OS from killing the listener
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CloudNest Watcher")
                .setContentText("Monitoring folders for new files...")
                .setSmallIcon(R.drawable.ic_file_generic)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();

        // FIX FOR SDK 34: Explicitly declare the foreground service type for Data Sync
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Load presets and start watching
        refreshObservers();

        return START_STICKY;
    }

    /**
     * Queries the database for all preset folders and attaches a FileObserver to each.
     */
    private void refreshObservers() {
        stopAllObservers();

        new Thread(() -> {
            List<PresetFolderEntity> presets = db.presetFolderDao().getAllPresetsSync();
            if (presets != null) {
                for (PresetFolderEntity folder : presets) {
                    startWatchingFolder(folder);
                }
            }
        }).start();
    }

    private void startWatchingFolder(PresetFolderEntity folder) {
        Log.d(TAG, "Starting watcher for: " + folder.localPath);
        CustomFileObserver observer = new CustomFileObserver(folder);
        observer.startWatching();
        observers.add(observer);
    }

    private void stopAllObservers() {
        for (CustomFileObserver observer : observers) {
            observer.stopWatching();
        }
        observers.clear();
    }

    @Override
    public void onDestroy() {
        stopAllObservers();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Folder Monitoring Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Internal class to handle File system events.
     */
    private class CustomFileObserver extends FileObserver {
        private final PresetFolderEntity folder;

        public CustomFileObserver(PresetFolderEntity folder) {
            super(folder.localPath, FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE);
            this.folder = folder;
        }

        @Override
        public void onEvent(int event, @Nullable String path) {
            if (path == null) return;

            Log.d(TAG, "File change detected in " + folder.folderName + ": " + path);
            
            // Trigger the SyncScheduler to start the AutoBackupWorker immediately
            SyncScheduler.triggerImmediateSync(getApplicationContext(), folder);
        }
    }
}