package com.adnostr.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.adnostr.app.databinding.ActivityMainBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main Interface Host for AdNostr.
 * UPDATED: Fixed Settings navigation glitch and added full hardware permission handling.
 * FIXED: Added Overlay Permission (SYSTEM_ALERT_WINDOW) check to allow Ads to pop up from background.
 * FIXED: Implemented Global Ad Listener to ensure Ads pop up even when switching between User and Advertiser roles.
 * NEW: Integrated Ad History saving logic and middle-tab navigation for Ads History.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
    private static final int PERMISSION_REQUEST_CODE = 2002;
    
    private ActivityMainBinding binding;
    private AdNostrDatabaseHelper db;
    private NavController navController;
    private WebSocketClientManager wsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);
        wsManager = WebSocketClientManager.getInstance();
        wsManager.init(this);

        // 2. Setup the Toolbar
        setSupportActionBar(binding.toolbar);

        // 3. Setup Navigation Controller
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Configure the Bottom Navigation with the NavController
            NavigationUI.setupWithNavController(binding.bottomNav, navController);

            // UPDATED: Dynamically adjust UI/Menu and Top Level Destinations
            configureRoleBasedUI();
        }

        // 4. Request Permissions for GPS (Maps), Storage (IPFS), and Background Overlays
        checkAndRequestAppPermissions();

        // 5. Start Background Sync Service
        startBackgroundAdListener();

        // 6. FIXED: Setup Global Ad Monitoring Listener
        // This keeps the ad listener alive even if the UserDashboardFragment is destroyed
        setupGlobalAdListener();
    }

    /**
     * Logic to handle Location, Storage, and Notification permissions.
     */
    private void checkAndRequestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display over other apps' to receive Ads instantly.", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * FIXED: Global Ad Listener stays active across the entire app lifecycle.
     * Captures Kind 30001 events and triggers the AdPopupActivity on the UI thread.
     * UPDATED: Now includes strict 'd' tag validation and saves valid ads to the History DB.
     */
    private void setupGlobalAdListener() {
        wsManager.setStatusListener(new WebSocketClientManager.RelayStatusListener() {
            @Override
            public void onRelayConnected(String url) {
                Log.d(TAG, "Global Listener: Connected to " + url);
            }

            @Override
            public void onRelayDisconnected(String url, String reason) {
                Log.d(TAG, "Global Listener: Disconnected from " + url);
            }

            @Override
            public void onMessageReceived(String url, String message) {
                try {
                    if (message.startsWith("[")) {
                        JSONArray msgArray = new JSONArray(message);
                        if ("EVENT".equals(msgArray.getString(0))) {
                            JSONObject event = msgArray.getJSONObject(2);
                            int kind = event.optInt("kind", -1);

                            // Only proceed if it is a verified Ad Event (Kind 30001)
                            if (kind == 30001) {
                                String contentStr = event.optString("content", "");
                                
                                // Peek logic to ensure this isn't an empty payload
                                if (contentStr.isEmpty() || !contentStr.contains("\"title\"")) {
                                    return; 
                                }

                                // Verify the 'd' tag to ensure it's a real Ad broadcast
                                boolean isAdBroadcast = false;
                                JSONArray tags = event.optJSONArray("tags");
                                if (tags != null) {
                                    for (int i = 0; i < tags.length(); i++) {
                                        JSONArray tagPair = tags.optJSONArray(i);
                                        if (tagPair != null && tagPair.length() >= 2) {
                                            String tagName = tagPair.optString(0);
                                            String tagValue = tagPair.optString(1);

                                            if ("d".equals(tagName)) {
                                                if (tagValue.startsWith("adnostr_ad_")) {
                                                    isAdBroadcast = true;
                                                    break;
                                                } else if ("adnostr_interests".equals(tagValue)) {
                                                    return; // Ignore user interest broadcasts
                                                }
                                            }
                                        }
                                    }
                                }

                                // Trigger Popup and Save to History if monitoring is active
                                if (isAdBroadcast && db.isListening()) {
                                    
                                    // Save the valid ad to the User History DB
                                    db.saveToUserHistory(message);

                                    runOnUiThread(() -> {
                                        Intent intent = new Intent(MainActivity.this, AdPopupActivity.class);
                                        intent.putExtra("AD_PAYLOAD_JSON", message);
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(intent);
                                    });
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Global ad processing failed: " + e.getMessage());
                }
            }

            @Override
            public void onError(String url, Exception ex) {
                Log.e(TAG, "Global Listener Error on " + url + ": " + ex.getMessage());
            }
        });
    }

    /**
     * Adjusts the visible menu items ensuring Settings is ALWAYS available.
     * UPDATED: Now ensures the nav_ads_history (middle tab) is visible for both roles.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        Menu menu = binding.bottomNav.getMenu();

        // Define top level destinations for the Action Bar
        AppBarConfiguration.Builder builder = new AppBarConfiguration.Builder(
                R.id.nav_user_dashboard, 
                R.id.nav_advertiser_dashboard,
                R.id.nav_ads_history,
                R.id.nav_settings
        );
        
        NavigationUI.setupActionBarWithNavController(this, navController, builder.build());

        // Standard middle tab is always visible for both roles
        menu.findItem(R.id.nav_ads_history).setVisible(true);

        if (RoleSelectionActivity.ROLE_USER.equals(role)) {
            menu.findItem(R.id.nav_user_dashboard).setVisible(true);
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(false);
            menu.findItem(R.id.nav_create_ad).setVisible(false);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(false);
            menu.findItem(R.id.nav_settings).setVisible(true);
            
            // Default landing for User
            if (navController.getCurrentDestination() != null && 
                navController.getCurrentDestination().getId() == R.id.nav_advertiser_dashboard) {
                navController.navigate(R.id.nav_user_dashboard);
            }
        } else {
            menu.findItem(R.id.nav_user_dashboard).setVisible(false);
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(true);
            menu.findItem(R.id.nav_create_ad).setVisible(true);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(true);
            menu.findItem(R.id.nav_settings).setVisible(true);
            
            // Default landing for Advertiser
            if (navController.getCurrentDestination() != null && 
                navController.getCurrentDestination().getId() == R.id.nav_user_dashboard) {
                navController.navigate(R.id.nav_advertiser_dashboard);
            }
        }
    }

    private void startBackgroundAdListener() {
        if (RoleSelectionActivity.ROLE_USER.equals(db.getUserRole())) {
            PeriodicWorkRequest adListenRequest = new PeriodicWorkRequest.Builder(
                    NostrListenerWorker.class, 
                    15, TimeUnit.MINUTES)
                    .addTag("NOSTR_AD_LISTENER")
                    .build();

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "AdNostrBackgroundSync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    adListenRequest
            );
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    public void refreshRoleAndUI() {
        configureRoleBasedUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions denied. Some features will not work.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }
}