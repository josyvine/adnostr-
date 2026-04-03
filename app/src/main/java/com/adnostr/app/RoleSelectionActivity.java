package com.adnostr.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.adnostr.app.databinding.ActivityRoleSelectionBinding;

/**
 * Onboarding On-Ramp: The "Two Paths" fork.
 * Allows the person to choose between standard User mode or Business Advertiser mode.
 * As per specification, this does NOT connect to a server; it only updates local state.
 */
public class RoleSelectionActivity extends AppCompatActivity {

    private static final String TAG = "AdNostr_RoleSelection";
    
    // Constant role identifiers for the database
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADVERTISER = "ADVERTISER";

    private ActivityRoleSelectionBinding binding;
    private AdNostrDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivityRoleSelectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 2. Initialize local database helper
        db = AdNostrDatabaseHelper.getInstance(this);

        // 3. Setup Listeners for the User Path
        binding.btnRegisterAsUser.setOnClickListener(v -> {
            Log.d(TAG, "User path selected. Initializing consumer mode.");
            handleRoleSelection(ROLE_USER);
        });

        // 4. Setup Listeners for the Advertiser Path
        binding.btnRegisterAsAdvertiser.setOnClickListener(v -> {
            Log.d(TAG, "Advertiser path selected. Initializing business mode.");
            handleRoleSelection(ROLE_ADVERTISER);
        });
    }

    /**
     * Persists the selected role to SharedPreferences and navigates 
     * to the Main dashboard area.
     */
    private void handleRoleSelection(String selectedRole) {
        try {
            // Save the choice locally
            db.saveUserRole(selectedRole);

            // Inform the user
            String message = selectedRole.equals(ROLE_USER) 
                ? "Privacy Mode Active: Welcome User!" 
                : "Broadcasting Mode Active: Welcome Advertiser!";
            
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

            // Navigate to the Main Host Activity
            // In the User Path, MainActivity will host interest hashtags.
            // In the Advertiser Path, MainActivity will host the Business Dashboard.
            Intent intent = new Intent(RoleSelectionActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            
            // Close the role selection screen permanently
            finish();

        } catch (Exception e) {
            // Log for diagnosis; AdNostrApplication will catch and show the Big Screen Error
            Log.e(TAG, "Role selection logic failed: " + e.getMessage());
            throw new RuntimeException("Role Selection Persistence Failure: " + e.getMessage(), e);
        }
    }

    /**
     * Override back button to prevent exiting onboarding without a choice.
     */
    @Override
    public void onBackPressed() {
        // Option 1: Toast to force choice
        Toast.makeText(this, "Please select a role to continue.", Toast.LENGTH_SHORT).show();
        // Option 2: super.onBackPressed() if you want to allow exit
    }
}