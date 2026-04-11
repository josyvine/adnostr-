package com.adnostr.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground Service to host the IPFS P2P Node.
 * Ensures that the Advertiser's images remain available for "leeching" by Users
 * even when the app is in the background.
 */
public class IPFSNodeService extends Service {

    private static final String TAG = "AdNostr_IPFSService";
    private static final String CHANNEL_ID = "ipfs_node_channel";
    private static final int NOTIFICATION_ID = 5005;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Starting IPFS Node Foreground Service...");

        // 1. Create the persistent notification required for Foreground Services
        Notification notification = createNotification();

        // 2. Start Foreground with 'dataSync' type for Android 14+ compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // 3. Initialize the actual P2P Node
        // FIX APPLIED: Wrapped in a background thread and try-catch block.
        // This prevents native Go Engine crashes from killing the entire app process.
        new Thread(() -> {
            try {
                IPFSNodeManager.getInstance(this).startNode();
            } catch (Exception e) {
                Log.e(TAG, "CRITICAL: P2P Node failed to start in background: " + e.getMessage());
            }
        }).start();

        return START_STICKY;
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AdNostr P2P Network")
                .setContentText("Your decentralized node is online and sharing ads.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Quiet notification
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "P2P Storage Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Keeps the decentralized IPFS node alive for ad sharing.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Stopping IPFS Node Service...");
        IPFSNodeManager.getInstance(this).stopNode();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't bind to this service, we just start it.
    }
}
