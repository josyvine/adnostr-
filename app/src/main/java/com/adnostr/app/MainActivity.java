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
 * Dynamically switches layouts between User Mode and Advertiser Mode.
 * UPDATED: Handles storage permissions for IPFS and stabilizes Settings navigation.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
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

            // Dynamically adjust UI/Menu based on User Role
            configureRoleBasedUI();
        }

        // 4. Request necessary permissions for IPFS and Location
        checkAndRequestPermissions();

        // 5. Start the Background Nostr Listener (WorkManager)
        startBackgroundAdListener();
    }

    /**
     * Ensures app has permissions to upload images and access location.
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsAssign = new ArrayList<>();
        for (String perm : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsAssign.add(perm);
            }
        }

        if (!listPermissionsAssign.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsAssign.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Adjusts the visible menu items and initial destination 
     * based on whether the person is a User or an Advertiser.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        Log.i(TAG, "Configuring UI for Role: " + role);

        Menu menu = binding.bottomNav.getMenu();

        if (RoleSelectionActivity.ROLE_USER.equals(role)) {
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(false);
            menu.findItem(R.id.nav_create_ad).setVisible(false);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(false);
            menu.findItem(R.id.nav_user_dashboard).setVisible(true);
            menu.findItem(R.id.nav_settings).setVisible(true);
            
            navController.navigate(R.id.nav_user_dashboard);
            
        } else if (RoleSelectionActivity.ROLE_ADVERTISER.equals(role)) {
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
            Log.d(TAG, "Starting Background Nostr Relay Listener...");
            
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
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for full functionality.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}