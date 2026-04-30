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
import com.google.android.material.badge.BadgeDrawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main Interface Host for AdNostr.
 * UPDATED: Replaced BottomNavigationView with ViewPager2 + TabLayout to support Swiping.
 * FIXED: Implemented Dynamic TabLayout labels to fix squashed icons and text.
 * FIXED: Role-based separation (3 tabs for User, 5 tabs for Advertiser).
 * FIXED: Overlay Permission (SYSTEM_ALERT_WINDOW) check for background ads.
 * FIXED: Global Ad Listener for Kind 30001 with 'd' tag validation.
 * 
 * ENHANCEMENT: Integrated Professional Material Navigation Icons for both User and Advertiser paths.
 * ENHANCEMENT: Implemented dynamic TabMode switching to prevent text truncation in Advertiser mode.
 * ENHANCEMENT: Added "Nearby" tab for real-time discovery (Feature 3).
 * FIXED (Glitch 4): Forced MODE_SCROLLABLE for User role to prevent text truncation on tab labels.
 * CRITICAL FIX FOR POPUP: Switched to addStatusListener to support the Global Ad Observer pattern.
 * ENHANCEMENT: Added "Console" tab to Bottom Navigation Bar.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Forensic Authority: Unlocks the "Report" tab for verified Admin identity.
 * - Live Alert Engine: Real-time Red Badge counter for unseen crowdsourced metadata.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
    private static final int PERMISSION_REQUEST_CODE = 2002;

    private ActivityMainBinding binding;
    private AdNostrDatabaseHelper db;
    private WebSocketClientManager wsManager;
    private MainViewPagerAdapter pagerAdapter;

    // ADMIN SUPREMACY: Index of the Report tab for badge targeting
    private int reportTabIndex = -1;

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

        // 3. Setup Dynamic Navigation System (Now including Nearby and Publisher tabs)
        setupNavigationSystem();

        // 4. Request Permissions for GPS, Media, and Overlays
        checkAndRequestAppPermissions();

        // 5. Start Background Sync Service
        startBackgroundAdListener();

        // 6. Setup Global Ad Monitoring Listener
        setupGlobalAdListener();
    }

    /**
     * Initializes ViewPager2 and TabLayout with dynamic labels.
     * This fixes the "squashed" icons by ensuring the tab count matches the role.
     * UPDATED: Using professional Material icons (ic_nav_...) instead of system drawables.
     * UPDATED: Implements dynamic MODE_SCROLLABLE for Advertisers to prevent text truncation.
     * FEATURE 3: Added Nearby Tab logic.
     * FIXED: Enabled Scrollable mode for both roles to prevent truncated text in User mode.
     * ADMIN: Appends the Forensic Report tab if isAdmin() is verified.
     */
    private void setupNavigationSystem() {
        String role = db.getUserRole();
        boolean isAdmin = db.isAdmin();
        
        pagerAdapter = new MainViewPagerAdapter(this, role);
        binding.mainViewPager.setAdapter(pagerAdapter);

        // Fix swipe performance
        binding.mainViewPager.setOffscreenPageLimit(10); 

        // ENHANCEMENT: Dynamic Tab Mode selection
        // FIXED: Replaced MODE_FIXED with MODE_SCROLLABLE for Users to stop text truncation.
        // Professional scrollable mode ensures labels like "INTERESTS" and "HISTORY" render fully.
        binding.tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);

        // Link TabLayout with dynamic logic to ensure icons and names render correctly
        new TabLayoutMediator(binding.tabLayout, binding.mainViewPager, (tab, position) -> {
            if (RoleSelectionActivity.ROLE_USER.equals(role)) {
                // USER LABELS
                switch (position) {
                    case 0:
                        tab.setText("Interests");
                        tab.setIcon(R.drawable.ic_nav_explore);
                        break;
                    case 1:
                        tab.setText("History");
                        tab.setIcon(R.drawable.ic_nav_history);
                        break;
                    case 2:
                        tab.setText("Nearby");
                        tab.setIcon(R.drawable.ic_nav_nearby);
                        break;
                    case 3:
                        tab.setText("Console");
                        tab.setIcon(R.drawable.terminal_2_24px);
                        break;
                    case 4:
                        tab.setText("Settings");
                        tab.setIcon(R.drawable.ic_nav_settings);
                        break;
                    case 5:
                        // ADMIN SUPREMACY: Report Tab for Users
                        if (isAdmin) {
                            tab.setText("Report");
                            tab.setIcon(R.drawable.ic_nav_report);
                            reportTabIndex = 5;
                        }
                        break;
                }
            } else {
                // ADVERTISER LABELS
                switch (position) {
                    case 0:
                        tab.setText("Stats");
                        tab.setIcon(R.drawable.ic_nav_stats);
                        break;
                    case 1:
                        tab.setText("History");
                        tab.setIcon(R.drawable.ic_nav_history);
                        break;
                    case 2:
                        tab.setText("Broadcast");
                        tab.setIcon(R.drawable.ic_nav_broadcast);
                        break;
                    case 3:
                        tab.setText("Network");
                        tab.setIcon(R.drawable.ic_nav_network);
                        break;
                    case 4:
                        tab.setText("Publisher");
                        tab.setIcon(R.drawable.ic_nav_publisher);
                        break;
                    case 5:
                        tab.setText("Nearby");
                        tab.setIcon(R.drawable.ic_nav_nearby);
                        break;
                    case 6:
                        tab.setText("Console");
                        tab.setIcon(R.drawable.terminal_2_24px);
                        break;
                    case 7:
                        tab.setText("Settings");
                        tab.setIcon(R.drawable.ic_nav_settings);
                        break;
                    case 8:
                        // ADMIN SUPREMACY: Report Tab for Advertisers
                        if (isAdmin) {
                            tab.setText("Report");
                            tab.setIcon(R.drawable.ic_nav_report);
                            reportTabIndex = 8;
                        }
                        break;
                }
            }
        }).attach();

        // Initial focus placement
        configureRoleBasedUI();
    }

    /**
     * Handles Location, Storage, and Notification permissions.
     * FEATURE 3: Ensure location permissions are strictly checked for Nearby features.
     */
    private void checkAndRequestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Enable 'Display over other apps' to receive Ads.", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        
        // Android 14 Foreground Service requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION);
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
     * Monitors Relays for Kind 30001 Ads and Admin Schema Events.
     * Validates 'd' tag to filter out User Interest broadcasts.
     * FIXED FOR POPUP: Uses addStatusListener to ensure MainActivity always stays attached 
     * and triggers the AdPopupActivity regardless of fragment state.
     * ADMIN SUPREMACY: Sniffs for 30006/30007 to update the real-time Report badge.
     */
    private void setupGlobalAdListener() {
        wsManager.addStatusListener(new WebSocketClientManager.RelayStatusListener() {
            @Override
            public void onRelayConnected(String url) {
                Log.d(TAG, "Connected to " + url);
                
                // ADMIN SUPREMACY: If Admin is connected, subscribe to schema updates globally
                if (db.isAdmin()) {
                    try {
                        JSONObject filter = new JSONObject();
                        filter.put("kinds", new JSONArray().put(30006).put(30007));
                        String subId = "admin-sniff-" + url.hashCode();
                        String req = new JSONArray().put("REQ").put(subId).put(filter).toString();
                        wsManager.subscribe(url, req);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onRelayDisconnected(String url, String reason) {
                Log.d(TAG, "Disconnected from " + url);
            }

            @Override
            public void onMessageReceived(String url, String message) {
                try {
                    if (message.startsWith("[")) {
                        JSONArray msgArray = new JSONArray(message);
                        if ("EVENT".equals(msgArray.getString(0))) {
                            JSONObject event = msgArray.getJSONObject(2);
                            int kind = event.optInt("kind", -1);

                            // --- PART 1: AD BROADCAST LOGIC (EXISTING) ---
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
                                            if ("d".equals(tagPair.getString(0)) && tagPair.getString(1).startsWith("adnostr_ad_")) {
                                                isAdBroadcast = true;
                                                break;
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
                            
                            // --- PART 2: ADMIN SUPREMACY ALERT LOGIC (NEW) ---
                            else if (db.isAdmin() && (kind == 30006 || kind == 30007)) {
                                long eventTime = event.optLong("created_at", 0);
                                if (eventTime > db.getReportLastSeen() && reportTabIndex != -1) {
                                    runOnUiThread(() -> {
                                        TabLayout.Tab reportTab = binding.tabLayout.getTabAt(reportTabIndex);
                                        if (reportTab != null) {
                                            BadgeDrawable badge = reportTab.getOrCreateBadge();
                                            badge.setNumber(badge.getNumber() + 1);
                                            badge.setVisible(true);
                                            badge.setBackgroundColor(ContextCompat.getColor(MainActivity.this, android.R.color.holo_red_dark));
                                        }
                                    });
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Message processing failed: " + e.getMessage());
                }
            }

            @Override
            public void onError(String url, Exception ex) {
                Log.e(TAG, "Relay Error: " + ex.getMessage());
            }
        });
    }

    /**
     * Ensures the correct tab is selected when the app opens.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        if (RoleSelectionActivity.ROLE_USER.equals(role)) {
            binding.mainViewPager.setCurrentItem(0, false);
        } else {
            binding.mainViewPager.setCurrentItem(0, false);
        }
    }

    private void startBackgroundAdListener() {
        if (RoleSelectionActivity.ROLE_USER.equals(db.getUserRole())) {
            PeriodicWorkRequest adListenRequest = new PeriodicWorkRequest.Builder(
                    NostrListenerWorker.class, 15, TimeUnit.MINUTES)
                    .addTag("NOSTR_AD_LISTENER").build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "AdNostrBackgroundSync", ExistingPeriodicWorkPolicy.KEEP, adListenRequest);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return super.onSupportNavigateUp();
    }

    /**
     * Safely refreshes the navigation when a role is switched.
     * Fixes the NullPointerException by ensuring fragments are re-setup properly.
     */
    public void refreshRoleAndUI() {
        runOnUiThread(() -> {
            setupNavigationSystem();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions denied.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }
}