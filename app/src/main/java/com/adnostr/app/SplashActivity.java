package com.adnostr.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivitySplashBinding;

/**
 * Entry point for AdNostr.
 * Handles identity generation and navigation logic based on user setup.
 * ENHANCEMENT: Added JSON Import interception to restore an existing identity.
 */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Splash";
    private static final int SPLASH_DELAY_MS = 2500;
    
    private ActivitySplashBinding binding;
    private AdNostrDatabaseHelper db;

    // ENHANCEMENT: Handlers to manage and cancel the auto-login countdown
    private Handler splashHandler;
    private Runnable splashRunnable;
    
    // ENHANCEMENT: Launcher for the Android Storage Access Framework (SAF)
    private ActivityResultLauncher<Intent> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 2. Initialize Database Helper for local settings storage
        db = AdNostrDatabaseHelper.getInstance(this);

        // ENHANCEMENT: Register the launcher for IMPORTING the JSON backup
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        if (result.getData().getData() != null) {
                            // Trigger the BackupManager to read the file and restore the account
                            BackupManager.importProfileFromJson(this, result.getData().getData());
                        }
                    } else {
                        // User cancelled the file picker, resume the normal key generation/login flow
                        checkIdentityAndNavigate();
                    }
                }
        );

        // ENHANCEMENT: Setup click listener for the new Import button
        binding.tvImportIdentity.setOnClickListener(v -> {
            // Stop the app from auto-generating a new key
            if (splashHandler != null && splashRunnable != null) {
                splashHandler.removeCallbacks(splashRunnable);
            }

            // Trigger the Android System File Picker
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            importLauncher.launch(intent);
        });

        // 3. Begin Identity Check & Navigation with a slight delay for branding
        splashHandler = new Handler(Looper.getMainLooper());
        splashRunnable = this::checkIdentityAndNavigate;
        splashHandler.postDelayed(splashRunnable, SPLASH_DELAY_MS);
    }

    /**
     * Ensures the device has a valid Nostr Private/Public keypair.
     * Then determines if the user needs to select a role or go to the dashboard.
     */
    private void checkIdentityAndNavigate() {
        try {
            // Check if private key already exists on this device
            String privateKey = db.getPrivateKey();

            if (privateKey == null || privateKey.isEmpty()) {
                Log.i(TAG, "First launch detected. Generating decentralized identity...");
                
                // Generate a new Schnorr/Secp256k1 keypair via the Key Manager
                String[] newKeys = NostrKeyManager.generateKeyPair();
                
                // Save keys securely to local storage
                db.savePrivateKey(newKeys[0]); // hex private key
                db.savePublicKey(newKeys[1]);  // hex public key (npub source)
                
                Log.i(TAG, "Identity created: " + newKeys[1]);
            } else {
                Log.i(TAG, "Existing identity verified: " + db.getPublicKey());
            }

            // Route user based on setup status
            proceedToNextScreen();

        } catch (Exception e) {
            // This will be caught by our Global Crash Watcher in AdNostrApplication,
            // but we log it here for extra diagnostic info.
            Log.e(TAG, "Failure during splash initialization: " + e.getMessage());
            throw new RuntimeException("Splash Initialization Failed: " + e.getMessage(), e);
        }
    }

    /**
     * Logic to determine where the user lands next.
     */
    private void proceedToNextScreen() {
        Intent intent;
        
        // If the user hasn't selected whether they are a User or Advertiser yet
        if (db.getUserRole() == null || db.getUserRole().isEmpty()) {
            Log.d(TAG, "No role found. Redirecting to Onboarding (Role Selection).");
            intent = new Intent(SplashActivity.this, RoleSelectionActivity.class);
        } else {
            Log.d(TAG, "Active session found as " + db.getUserRole() + ". Opening Dashboard.");
            intent = new Intent(SplashActivity.this, MainActivity.class);
        }

        startActivity(intent);
        
        // Remove Splash from the backstack so user can't return to it
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Prevent memory leaks if activity is closed during the delay
        if (splashHandler != null && splashRunnable != null) {
            splashHandler.removeCallbacks(splashRunnable);
        }
    }
}