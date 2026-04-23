package com.adnostr.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * FEATURE 3: Live Location Beacon Service.
 * Acts as a foreground service that periodically broadcasts GPS coordinates 
 * to the Nostr network using Kind 30004.
 * 
 * Logic: Get Location -> Wrap in JSON -> Master Encrypt -> Sign Kind 30004 -> Broadcast.
 * FORENSIC UPDATE: Implements persistent background tracing for radar diagnostics.
 */
public class LocationUpdateService extends Service {

    private static final String TAG = "AdNostr_LocService";
    private static final String CHANNEL_ID = "location_beacon_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // Broadcast interval: 5 minutes (to balance battery and real-time discovery)
    private static final long UPDATE_INTERVAL_MS = 5 * 60 * 1000;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;

    @Override
    public void onCreate() {
        super.onCreate();
        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        logBackgroundForensic("SERVICE_CREATED: Location Beacon Engine Initialized.");
        setupLocationCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Location Beacon Service Started.");
        logBackgroundForensic("SERVICE_START: Background beaconing is now ACTIVE.");

        // 1. Create Notification Channel for Foreground Service
        createNotificationChannel();

        // 2. Build the persistent notification (Required for Foreground Services)
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AdNostr Nearby Discovery")
                .setContentText("Your location beacon is active and encrypted.")
                .setSmallIcon(R.drawable.ic_nav_nearby)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        // 3. Start service in foreground
        startForeground(NOTIFICATION_ID, notification);

        // 4. Request location updates
        requestLocationUpdates();

        return START_STICKY;
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    logBackgroundForensic("GPS_POLL: Result is NULL. Check GPS signal.");
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    logBackgroundForensic("GPS_LOCK: Lat: " + location.getLatitude() + ", Lon: " + location.getLongitude() + " [Acc: " + location.getAccuracy() + "m]");
                    broadcastLocationToNostr(location);
                }
            }
        };
    }

    private void requestLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS / 2)
                .build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logBackgroundForensic("CRITICAL_ERROR: Fine location permission revoked. Service stopping.");
            stopSelf();
            return;
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        logBackgroundForensic("REQUEST_UPDATES: GPS polling configured for " + (UPDATE_INTERVAL_MS/1000) + "s interval.");
    }

    /**
     * Constructs and broadcasts the Nostr Kind 30004 event.
     */
    private void broadcastLocationToNostr(Location location) {
        try {
            // 1. Prepare Payload
            JSONObject locJson = new JSONObject();
            locJson.put("lat", location.getLatitude());
            locJson.put("lon", location.getLongitude());
            locJson.put("role", db.getUserRole());
            
            // Include username if not hidden (Feature 1 compatibility)
            if (!db.isUsernameHidden()) {
                locJson.put("name", db.getUsername());
            }

            logBackgroundForensic("PAYLOAD_RAW: " + locJson.toString());

            // 2. Encrypt via Master App-Level Key
            logBackgroundForensic("CRYPTO_START: Wrapping payload in Master AES-256...");
            String encryptedContent = EncryptionUtils.encryptPayload(locJson.toString());
            logBackgroundForensic("CRYPTO_OK: Encrypted Hex -> " + encryptedContent.substring(0, 16) + "...");

            // 3. Construct Nostr Event (Kind 30004)
            JSONObject event = new JSONObject();
            event.put("kind", 30004);
            event.put("pubkey", db.getPublicKey());
            event.put("created_at", System.currentTimeMillis() / 1000);
            event.put("content", encryptedContent);

            // Parameterized Replaceable Event tag
            JSONArray tags = new JSONArray();
            JSONArray dTag = new JSONArray();
            dTag.put("d");
            dTag.put("adnostr_location_beacon");
            tags.put(dTag);
            event.put("tags", tags);

            // 4. Sign and Broadcast
            logBackgroundForensic("SIGN_START: Executing BIP-340 Schnorr signature...");
            JSONObject signedEvent = NostrEventSigner.signEvent(db.getPrivateKey(), event);
            
            if (signedEvent != null) {
                logBackgroundForensic("SIGN_OK: Event ID -> " + signedEvent.getString("id"));
                
                wsManager.broadcastEvent(signedEvent.toString());
                logBackgroundForensic("NETWORK_PUSH: Beacon passed to WebSocket Manager.");
                Log.d(TAG, "Location Beacon Broadcasted: " + location.getLatitude() + ", " + location.getLongitude());
            } else {
                logBackgroundForensic("SIGN_FAIL: Signature engine returned NULL.");
            }

        } catch (Exception e) {
            logBackgroundForensic("BROADCAST_EXCEPTION: " + e.getMessage());
            Log.e(TAG, "Failed to broadcast location: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Nearby Discovery Beacon",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * Internal forensic logger. Writes to SharedPreferences so NearbyFragment can 
     * pull background logs for its diagnostic console display.
     */
    private void logBackgroundForensic(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String entry = "[" + time + "] " + msg + "\n";
        
        SharedPreferences logPrefs = getSharedPreferences("adnostr_background_logs", MODE_PRIVATE);
        String currentLogs = logPrefs.getString("trace", "");
        
        // Keep the last 10,000 characters of background trace
        String updatedLogs = entry + currentLogs;
        if (updatedLogs.length() > 10000) {
            updatedLogs = updatedLogs.substring(0, 8000);
        }
        
        logPrefs.edit().putString("trace", updatedLogs).apply();
        Log.i(TAG, "Forensic Trace: " + msg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logBackgroundForensic("SERVICE_DESTROYED: Beaconing terminated.");
        fusedLocationClient.removeLocationUpdates(locationCallback);
        Log.i(TAG, "Location Beacon Service Stopped.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}