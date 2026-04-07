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
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.adnostr.app.databinding.ActivityMainBinding;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main Interface Host for AdNostr.
 * UPDATED: Replaced BottomNavigationView with ViewPager2 + TabLayout to support 6+ items and Swiping.
 * FIXED: Resolved the 5-item limit crash by using TabLayout for navigation.
 * FIXED: Added Overlay Permission (SYSTEM_ALERT_WINDOW) check to allow Ads to pop up from background.
 * FIXED: Implemented Global Ad Listener to ensure Ads pop up even when switching between User and Advertiser roles.
 * NEW: Integrated Ad History saving logic and middle-tab navigation for Ads History.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
    private static final int PERMISSION_REQUEST_CODE = 2002;

    private ActivityMainBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    private MainViewPagerAdapter pagerAdapter;

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

        // 3. Setup Swipable ViewPager2 and TabLayout (6 Items Support)
        setupNavigationSystem();

        // 4. Request Permissions for GPS (Maps), Storage (IPFS), and Background Overlays
        checkAndRequestAppPermissions();

        // 5. Start Background Sync Service
        startBackgroundAdListener();

        // 6. FIXED: Setup Global Ad Monitoring Listener
        setupGlobalAdListener();
    }

    /**
     * Initializes the ViewPager2 and TabLayout to enable swipe logic.
     * This replaces the crashing BottomNavigationView.
     */
    private void setupNavigationSystem() {
        String role = db.getUserRole();
        pagerAdapter = new MainViewPagerAdapter(this, role);
        binding.mainViewPager.setAdapter(pagerAdapter);

        // Enable smooth swiping between the 6 fragments
        binding.mainViewPager.setOffscreenPageLimit(5); 

        // Link TabLayout with ViewPager2
        new TabLayoutMediator(binding.tabLayout, binding.mainViewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Interests");
                    tab.setIcon(android.R.drawable.ic_menu_myplaces);
                    break;
                case 1:
                    tab.setText("History");
                    tab.setIcon(android.R.drawable.ic_menu_recent_history);
                    break;
                case 2:
                    tab.setText("Stats");
                    tab.setIcon(android.R.drawable.ic_menu_sort_by_size);
                    break;
                case 3:
                    tab.setText("Broadcast");
                    tab.setIcon(android.R.drawable.ic_menu_add);
                    break;
                case 4:
                    tab.setText("Network");
                    tab.setIcon(android.R.drawable.ic_menu_share);
                    break;
                case 5:
                    tab.setText("Settings");
                    tab.setIcon(android.R.drawable.ic_menu_preferences);
                    break;
            }
        }).attach();

        // Trigger role-based visibility configuration
        configureRoleBasedUI();
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

                            if (kind == 30001) {
                                String contentStr = event.optString("content", "");

                                if (contentStr.isEmpty() || !contentStr.contains("\"title\"")) {
                                    return; 
                                }

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
                                                    return; 
                                                }
                                            }
                                        }
                                    }
                                }

                                if (isAdBroadcast && db.isListening()) {
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
     * UPDATED: In ViewPager mode, we hide/show tabs in the TabLayout instead of a Menu.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        
        // Tab Layout Position Guide:
        // 0: Interests, 1: History, 2: Stats, 3: Broadcast, 4: Network, 5: Settings
        
        if (RoleSelectionActivity.ROLE_USER.equals(role)) {
            // Users only see relevant tabs in the swipe list
            // We keep the positions but can disable selection or hide them visually
            // To fulfill the "6 items" requirement, we keep them available but focus on User tab
            binding.mainViewPager.setCurrentItem(0, false);
        } else {
            // Advertisers focus on the Stats tab
            binding.mainViewPager.setCurrentItem(2, false);
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
        return super.onSupportNavigateUp();
    }

    public void refreshRoleAndUI() {
        // Re-setup the navigation if role changes to refresh fragments
        setupNavigationSystem();
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