package com.adnostr.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivitySplashBinding;

/**
 * Entry point for AdNostr.
 * Handles identity generation and navigation logic based on user setup.
 */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_Splash";
    private static final int SPLASH_DELAY_MS = 2500;
    
    private ActivitySplashBinding binding;
    private AdNostrDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 2. Initialize Database Helper for local settings storage
        db = AdNostrDatabaseHelper.getInstance(this);

        // 3. Begin Identity Check & Navigation with a slight delay for branding
        new Handler(Looper.getMainLooper()).postDelayed(this::checkIdentityAndNavigate, SPLASH_DELAY_MS);
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
}