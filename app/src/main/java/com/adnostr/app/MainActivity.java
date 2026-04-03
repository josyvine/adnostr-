package com.adnostr.app;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.adnostr.app.databinding.ActivityMainBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.concurrent.TimeUnit;

/**
 * Main Interface Host for AdNostr.
 * Dynamically switches layouts between User Mode and Advertiser Mode.
 * Initializes background WorkManager to listen for decentralized ad broadcasts.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Main";
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

        // 4. Start the Background Nostr Listener (WorkManager)
        // This ensures the app receives targeted ads even when not in the foreground.
        startBackgroundAdListener();
    }

    /**
     * Adjusts the visible menu items and initial destination 
     * based on whether the person is a User or an Advertiser.
     */
    private void configureRoleBasedUI() {
        String role = db.getUserRole();
        Log.i(TAG, "Configuring UI for Role: " + role);

        Menu menu = binding.bottomNav.getMenu();

        if (role.equals(RoleSelectionActivity.ROLE_USER)) {
            // Path A: User UI
            // Hide Advertiser-specific tabs if they exist in the shared menu
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(false);
            menu.findItem(R.id.nav_create_ad).setVisible(false);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(false);
            
            // Show User-specific tabs
            menu.findItem(R.id.nav_user_dashboard).setVisible(true);
            
            // Navigate to User Dashboard initially
            navController.navigate(R.id.nav_user_dashboard);
            
        } else if (role.equals(RoleSelectionActivity.ROLE_ADVERTISER)) {
            // Path B: Advertiser UI
            // Hide User-specific tabs
            menu.findItem(R.id.nav_user_dashboard).setVisible(false);
            
            // Show Advertiser-specific tabs
            menu.findItem(R.id.nav_advertiser_dashboard).setVisible(true);
            menu.findItem(R.id.nav_create_ad).setVisible(true);
            menu.findItem(R.id.nav_relay_marketplace).setVisible(true);
            
            // Navigate to Advertiser Dashboard initially
            navController.navigate(R.id.nav_advertiser_dashboard);
        }
    }

    /**
     * Schedules a periodic WorkManager task to connect to Nostr Relays 
     * and check for new kind:30001 events (Ads).
     */
    private void startBackgroundAdListener() {
        // We only start listening if the user is in "USER" mode
        if (db.getUserRole().equals(RoleSelectionActivity.ROLE_USER)) {
            Log.d(TAG, "Starting Background Nostr Relay Listener...");
            
            PeriodicWorkRequest adListenRequest = new PeriodicWorkRequest.Builder(
                    NostrListenerWorker.class, 
                    15, TimeUnit.MINUTES) // Android minimum periodic interval
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

    /**
     * Handles switching roles from the Settings menu later.
     */
    public void refreshRoleAndUI() {
        configureRoleBasedUI();
    }
}