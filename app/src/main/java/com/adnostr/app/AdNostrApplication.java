package com.adnostr.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Global Application class for AdNostr.
 * Handles notification channel initialization and the Global Uncaught Exception Handler.
 * UPDATED: Removed IPFS Node Service logic to support the new Encrypted HTTP Media system.
 */
public class AdNostrApplication extends Application {

    public static final String AD_NOTIFICATION_CHANNEL_ID = "adnostr_deals_channel";
    private static final String TAG = "AdNostrApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Initialize the Global Crash Watchdog
        // This will catch any failure in any function across the entire app
        setupGlobalExceptionHandler();

        // 2. Initialize Notification Channels for Ad Alerts
        createNotificationChannels();

        // 3. P2P NODE STARTUP REMOVED
        // The Datahop/IPFS engine has been replaced with the NIP-96 Media Relay system.
        // Direct HTTP uploads via MediaUploadHelper now handle all media functionality.
        
        Log.i(TAG, "AdNostr Application Started and Protected by Crash Watchdog.");
    }

    /**
     * Configures the system to intercept all uncaught Java errors.
     * When a crash occurs, it gathers the stack trace and opens ErrorDisplayActivity.
     */
    private void setupGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                // Extract the detailed stack trace to a string for diagnosis
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                Log.e(TAG, "CRITICAL FAILURE DETECTED: " + stackTrace);

                // Create an intent to launch our Big Screen Error Activity
                // We use FLAG_ACTIVITY_NEW_TASK because we are outside an Activity context
                // and FLAG_ACTIVITY_CLEAR_TASK to ensure the crashed app state is wiped
                Intent intent = new Intent(getApplicationContext(), ErrorDisplayActivity.class);
                intent.putExtra("ERROR_DETAILS", stackTrace);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                // Trigger the popup
                startActivity(intent);

                // Force kill the crashed process to allow the error handler (on a separate process) to show
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * Creates the Android Notification Channel for ad broadcasts.
     * Required for Android 8.0 (API 26) and above.
     */
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Local Ad Notifications";
            String description = "Alerts you when a decentralized ad matching your interests is found.";
            int importance = NotificationManager.IMPORTANCE_HIGH;

            NotificationChannel channel = new NotificationChannel(AD_NOTIFICATION_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableVibration(true);
            channel.setShowBadge(true);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}