package com.cloudnest.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.cloudnest.app.databinding.ActivityMainBinding;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

/**
 * Main Host Activity for CloudNest.
 * Manages the Navigation Drawer, Floating Action Button (FAB),
 * Runtime Permissions, Back Button logic, and Global UI.
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int STORAGE_PERMISSION_CODE = 2002;

    private ActivityMainBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private GoogleSignInClient googleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup Toolbar - CORRECTED to access toolbar directly
        setSupportActionBar(binding.toolbar);

        // Initialize Google Sign-In Client (Used for Logout)
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        // Setup Navigation Component
        DrawerLayout drawer = binding.drawerLayout;
        NavigationView navigationView = binding.navView;

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);

        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Define top-level destinations (No back arrow, shows hamburger menu instead)
            appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_dashboard, 
                    R.id.nav_phone_storage, 
                    R.id.nav_sd_storage,
                    R.id.nav_drive_browser, 
                    R.id.nav_upload_manager, 
                    R.id.nav_storage_graph,
                    R.id.nav_preset_folders, 
                    R.id.nav_drive_accounts)
                    .setOpenableLayout(drawer)
                    .build();

            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(navigationView, navController);
        }

        // Handle custom navigation item clicks (like Logout)
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_logout) {
                confirmAndLogout();
                return true;
            }

            // Let NavigationUI handle standard fragment navigation
            boolean handled = NavigationUI.onNavDestinationSelected(item, navController);
            if (handled) {
                drawer.closeDrawer(GravityCompat.START);
            }
            return handled;
        });

        // Setup Floating Action Button (FAB) - CORRECTED to access fab directly
        binding.fab.setOnClickListener(view -> showFabMenu());

        // Setup custom Back Button logic (Exit Confirmation on Dashboard)
        setupBackButtonLogic();

        // Check and Request Runtime Permissions
        checkRuntimePermissions();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // Handles hamburger icon clicks and up navigation
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    /**
     * Intercepts the back button. 
     * If the user is on the Dashboard (Home), show an exit confirmation.
     * Otherwise, let the NavController handle the back stack.
     */
    private void setupBackButtonLogic() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else if (navController.getCurrentDestination() != null && 
                           navController.getCurrentDestination().getId() == R.id.nav_dashboard) {
                    showExitConfirmationDialog();
                } else {
                    // Not on dashboard, pop the back stack normally
                    if (!navController.navigateUp()) {
                        setEnabled(false);
                        onBackPressed();
                    }
                }
            }
        });
    }

    /**
     * Shows Exit Confirmation ("Exit CloudNest? Cancel / Exit")
     */
    private void showExitConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Exit CloudNest?")
                .setMessage("Are you sure you want to exit the application? Background uploads will continue.")
                .setPositiveButton("Exit", (dialog, which) -> finish())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Shows a dialog or bottom sheet for FAB actions.
     */
    private void showFabMenu() {
        // Implement bottom sheet or popup menu for: Create New Folder, Upload Files, Upload Folder
        String[] options = {"Create New Folder", "Upload Files", "Upload Folder"};
        new AlertDialog.Builder(this)
                .setTitle("Select Action")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Toast.makeText(this, "Create New Folder Selected", Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            Toast.makeText(this, "Upload Files Selected", Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            Toast.makeText(this, "Upload Folder Selected", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    /**
     * Confirms and executes Google Sign-Out.
     */
    private void confirmAndLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to disconnect your Google Drive account?")
                .setPositiveButton("Logout", (dialog, which) -> {
                    googleSignInClient.signOut().addOnCompleteListener(this, task -> {
                        Toast.makeText(MainActivity.this, "Successfully logged out.", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(MainActivity.this, SplashActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Manages all required runtime permissions for Android 11+ (Storage) and Android 13+ (Notifications).
     */
    private void checkRuntimePermissions() {
        // 1. Storage Permission (Manage External Storage for Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionExplanation();
            }
        } else {
            // Android 10 and below Storage Permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, STORAGE_PERMISSION_CODE);
            }
        }

        // 2. Post Notifications Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    /**
     * Directs the user to the All Files Access settings page for Android 11+.
     */
    private void showStoragePermissionExplanation() {
        new AlertDialog.Builder(this)
                .setTitle("Storage Access Required")
                .setMessage("CloudNest requires 'All Files Access' to backup your entire phone and SD card to Google Drive.")
                .setCancelable(false)
                .setPositiveButton("Grant Access", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, STORAGE_PERMISSION_CODE);
                    }
                })
                .setNegativeButton("Exit", (dialog, which) -> finish())
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage Permission Granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage Permission Denied. File browsing is disabled.", Toast.LENGTH_LONG).show();
            }
        }
    }
}