package com.adnostr.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main Interface Host for AdNostr.
 * UPDATED: Fixed Settings navigation glitch and added full hardware permission handling.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
    private static final int PERMISSION_REQUEST_CODE = 2002;
    
    private ActivityMainBinding binding;
    private AdNostrDatabaseHelper db;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = AdNostrDatabaseHelper.getInstance(this);

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

        // 4. Request Permissions for GPS (Maps) and Storage (IPFS)
        checkAndRequestAppPermissions();

        // 5. Start Ad Listener
        startBackgroundAdListener();
    }

    /**
     * Logic to handle Location, Storage, and Notification permissions.
     */
    private void checkAndRequestAppPermissions() {
        List<String> permissions = new ArrayList<>();
        
        // Location is needed for Advertiser Maps
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        
        // Storage/Media is needed for IPFS Image Selection
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
     * UPDATED: Adjusts the visible menu items ensuring Settings is ALWAYS available.
     * Prevents the glitch where Settings click does nothing in Advertiser mode.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        Menu menu = binding.bottomNav.getMenu();

        // Standard Top Level destinations (prevents "Up" button on main tabs)
        AppBarConfiguration.Builder builder = new AppBarConfiguration.Builder(
                R.id.nav_user_dashboard, 
                R.id.nav_advertiser_dashboard, 
                R.id.nav_settings
        );
        
        NavigationUI.setupActionBarWithNavController(this, navController, builder.build());

        if (RoleSelectionActivity.ROLE_USER.equals(role)) {
            // USER VIEW
            menu.findItem(R.id.nav_user_dashboard).setVisible(true);
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(false);
            menu.findItem(R.id.nav_create_ad).setVisible(false);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(false);
            menu.findItem(R.id.nav_settings).setVisible(true);
            
            navController.navigate(R.id.nav_user_dashboard);
            
        } else {
            // ADVERTISER VIEW
            menu.findItem(R.id.nav_user_dashboard).setVisible(false);
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(true);
            menu.findItem(R.id.nav_create_ad).setVisible(true);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(true);
            menu.findItem(R.id.nav_settings).setVisible(true);
            
            navController.navigate(R.id.nav_advertiser_dashboard);
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