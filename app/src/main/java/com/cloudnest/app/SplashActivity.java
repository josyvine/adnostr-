package com.cloudnest.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;

import java.util.concurrent.Executors;

/**
 * Entry point of the CloudNest Application (Landing Screen).
 * Handles Google OAuth 2.0 Authentication.
 * If the user is already authenticated, it redirects immediately to the Dashboard.
 */
public class SplashActivity extends AppCompatActivity {

    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> driveSignInLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Configure Google Sign-In and Request Required Drive API Scopes
        setupGoogleAuth();

        // 2. Check if user is already logged in
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null && GoogleSignIn.hasPermissions(account, getRequiredScopes())) {
            // User is authenticated and has given permissions, skip landing screen
            // --- FIXED: Ensure the logged-in account is saved to the local database ---
            saveAccountToDatabase(account);
            navigateToMain();
            return;
        }

        // 3. User is not logged in, display the Landing Screen UI
        setContentView(R.layout.activity_splash);

        // 4. Handle "Continue with Google" button click
        View btnGoogleLogin = findViewById(R.id.btn_google_login);
        if (btnGoogleLogin != null) {
            btnGoogleLogin.setOnClickListener(v -> startGoogleSignIn());
        }
    }

    /**
     * Initializes GoogleSignInClient with the specific OAuth Scopes required for CloudNest.
     */
    private void setupGoogleAuth() {
        GoogleSignInOptions.Builder gsoBuilder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile();

        // Request Drive Scopes as per your instruction specifications
        for (Scope scope : getRequiredScopes()) {
            gsoBuilder.requestScopes(scope);
        }

        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build());

        // Setup the modern ActivityResultLauncher for the sign-in intent
        driveSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        handleSignInResult(result.getData());
                    } else {
                        Toast.makeText(this, "Google Sign-In canceled or failed.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    /**
     * Defines the exact Google Drive API Scopes required by the app.
     */
    private Scope[] getRequiredScopes() {
        return new Scope[]{
                new Scope(DriveScopes.DRIVE_FILE),       // Manage files created by the app
                new Scope(DriveScopes.DRIVE_METADATA),   // Read/write file metadata (folders, etc.)
                new Scope(DriveScopes.DRIVE_READONLY)    // Read all files (required for full management)
        };
    }

    /**
     * Launches the Google OAuth Intent.
     */
    private void startGoogleSignIn() {
        Intent signInIntent = googleSignInClient.getSignInIntent();
        driveSignInLauncher.launch(signInIntent);
    }

    /**
     * Processes the result of the Google Sign-In attempt.
     */
    private void handleSignInResult(Intent data) {
        try {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            GoogleSignInAccount account = task.getResult(ApiException.class);

            if (account != null) {
                // Check if all requested scopes were granted by the user
                if (!GoogleSignIn.hasPermissions(account, getRequiredScopes())) {
                    // If not, request them again explicitly
                    GoogleSignIn.requestPermissions(
                            this,
                            1001,
                            account,
                            getRequiredScopes()
                    );
                } else {
                    // Success! Proceed to the main app dashboard.
                    // --- FIXED: Save account to database upon successful login ---
                    saveAccountToDatabase(account);
                    
                    Toast.makeText(this, "Welcome to CloudNest, " + account.getGivenName(), Toast.LENGTH_SHORT).show();
                    navigateToMain();
                }
            }
        } catch (ApiException e) {
            Toast.makeText(this, "Authentication failed: " + e.getStatusCode(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    /**
     * Helper method to save the Google account to the local Room database.
     * This ensures the Ledger has an account to display.
     */
    private void saveAccountToDatabase(GoogleSignInAccount account) {
        if (account == null || account.getEmail() == null) {
            return;
        }
        
        String email = account.getEmail();
        String name = account.getDisplayName();

        Executors.newSingleThreadExecutor().execute(() -> {
            CloudNestDatabase db = CloudNestDatabase.getInstance(getApplicationContext());
            // Only insert if it doesn't already exist to avoid duplicates
            if (db.driveAccountDao().getAccountByEmail(email) == null) {
                DriveAccountEntity newAccount = new DriveAccountEntity();
                newAccount.email = email;
                newAccount.displayName = name != null ? name : "Google Drive User";
                // If it's the first account, make it active by default
                if (db.driveAccountDao().getAccountCount() == 0) {
                    newAccount.isActive = true;
                } else {
                    newAccount.isActive = false;
                }
                newAccount.isFull = false;
                db.driveAccountDao().insert(newAccount);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                // Permissions granted via fallback, ensure account is saved
                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
                saveAccountToDatabase(account);
                navigateToMain();
            } else {
                Toast.makeText(this, "Drive permissions are required to use CloudNest.", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Redirects to the MainActivity and closes the SplashActivity so the user cannot navigate back to it.
     */
    private void navigateToMain() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}