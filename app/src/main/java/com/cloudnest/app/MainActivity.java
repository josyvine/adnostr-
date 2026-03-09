package com.cloudnest.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * UPDATED: Fixed FAB logic for Glitch 5 and handled notification permissions.
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

        // Setup Toolbar
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

            // Define top-level destinations
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

            // Hide/Show FAB based on fragment destination to prevent overlap
            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                if (destination.getId() == R.id.nav_preset_folders || destination.getId() == R.id.nav_drive_accounts) {
                    binding.fab.hide();
                } else {
                    binding.fab.show();
                }
            });
        }

        // Handle navigation item clicks
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_logout) {
                confirmAndLogout();
                return true;
            }

            boolean handled = NavigationUI.onNavDestinationSelected(item, navController);
            if (handled) {
                drawer.closeDrawer(GravityCompat.START);
            }
            return handled;
        });

        // Setup FAB Action Center
        binding.fab.setOnClickListener(view -> showFabMenu());

        // Setup Back Button logic (Exit Confirmation)
        setupBackButtonLogic();

        // Check and Request Runtime Permissions
        checkRuntimePermissions();
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

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
                    if (!navController.navigateUp()) {
                        setEnabled(false);
                        onBackPressed();
                    }
                }
            }
        });
    }

    private void showExitConfirmationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Exit CloudNest?")
                .setMessage("Background uploads will continue even if you close the app.")
                .setPositiveButton("Exit", (dialog, which) -> finish())
                .setNegativeButton("Stay", null)
                .show();
    }

    /**
     * UPDATED for Glitch 5: Provides clear action paths.
     */
    private void showFabMenu() {
        String[] options = {"Create New Folder", "Manual Upload (Pick Files)", "Auto-Backup (Sync Folder)"};
        new AlertDialog.Builder(this)
                .setTitle("Quick Actions")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showCreateFolderDialog();
                    } else if (which == 1) {
                        Toast.makeText(this, "Select files in 'Phone Storage' to upload.", Toast.LENGTH_LONG).show();
                        navController.navigate(R.id.nav_phone_storage);
                    } else if (which == 2) {
                        Toast.makeText(this, "Long-press a folder to start Auto-Backup.", Toast.LENGTH_LONG).show();
                        navController.navigate(R.id.nav_phone_storage);
                    }
                })
                .show();
    }

    /**
     * UPDATED: Fixed UI for folder naming.
     */
    private void showCreateFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Folder Name");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. Work Documents");
        
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(40, 20, 40, 20);
        input.setLayoutParams(lp);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (!folderName.isEmpty()) {
                Toast.makeText(this, "Creating '" + folderName + "'...", Toast.LENGTH_SHORT).show();
                // Logic to handle folder creation on Drive or Local could be triggered here
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmAndLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Disconnect your Google Drive account?")
                .setPositiveButton("Logout", (dialog, which) -> {
                    googleSignInClient.signOut().addOnCompleteListener(this, task -> {
                        Toast.makeText(MainActivity.this, "Logged out.", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(MainActivity.this, SplashActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showStoragePermissionExplanation();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, STORAGE_PERMISSION_CODE);
            }
        }

        // Notification permission for Glitch 8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void showStoragePermissionExplanation() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Needed")
                .setMessage("CloudNest requires file access to backup your local storage to Google Drive.")
                .setCancelable(false)
                .setPositiveButton("Grant", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
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
                Toast.makeText(this, "Storage access granted.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}