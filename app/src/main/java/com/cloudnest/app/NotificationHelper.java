package com.cloudnest.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Utility for managing CloudNest System Notifications.
 * Provides live feedback for uploads in the system tray.
 */
public class NotificationHelper {

    private static final String CHANNEL_ID = CloudNestApplication.UPLOAD_CHANNEL_ID;
    private static final int PROGRESS_NOTIFICATION_ID = 4001;
    private static final int FINISHED_NOTIFICATION_ID = 4002;

    /**
     * Displays or updates the "Uploading" progress bar notification.
     * @param context App context.
     * @param percent Progress (0 to 100).
     * @param status Descriptive text (e.g., "File 5 of 20 - 1.2 MB/s").
     */
    public static void showUploadProgress(Context context, int percent, String status) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_file_generic)
                .setContentTitle("Backing up to Google Drive")
                .setContentText(status)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true) // Stops the phone from beeping on every update
                .setProgress(100, percent, false);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        try {
            notificationManager.notify(PROGRESS_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            // Android 13 permission missing or denied
        }
    }

    /**
     * Shows the final "Success" notification when a task completes.
     * @param context App context.
     * @param fileCount Total number of files uploaded.
     */
    public static void showUploadComplete(Context context, int fileCount) {
        // Cancel the progress notification first
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_file_generic)
                .setContentTitle("Backup Finished")
                .setContentText("Successfully uploaded " + fileCount + " files to your CloudNest folder.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        try {
            notificationManager.notify(FINISHED_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            // Android 13 permission missing
        }
    }

    /**
     * Shows a "Failed" alert in the system tray.
     */
    public static void showUploadFailed(Context context) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_file_generic)
                .setContentTitle("Upload Error")
                .setContentText("Connection lost. Tap to view Upload Manager for retry.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        try {
            notificationManager.notify(FINISHED_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            // Android 13 permission missing
        }
    }

    /**
     * Ensures the Notification Channel is created for legacy systems.
     * Note: Usually handled in CloudNestApplication.
     */
    public static void checkNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CloudNest Uploads",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Progress indicators for folder and file backups.");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}