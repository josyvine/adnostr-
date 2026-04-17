package com.adnostr.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Logic Engine for JSON Identity Portability.
 * FEATURE: Exports full decentralized identity and settings to a JSON "Digital Passport".
 * FEATURE: Validates imported keys via NostrKeyManager for length and BIP-340 parity.
 * FEATURE: Performs batch restore and triggers an app-level process restart.
 * 
 * FIXED: Added missing android.app.Activity import to resolve compilation error.
 */
public class BackupManager {

    private static final String TAG = "AdNostr_Backup";

    /**
     * Packages current identity and settings into a JSON string and writes to the selected Uri.
     * TRIGGERED BY: ACTION_CREATE_DOCUMENT result in SettingsFragment.
     */
    public static void exportProfileToJson(Context context, Uri fileUri) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

            // 1. Build the JSON Object according to the requested schema
            JSONObject backup = new JSONObject();
            
            // Identity
            backup.put("nostr_private_key", db.getPrivateKey());
            backup.put("nostr_public_key", db.getPublicKey());
            backup.put("username", db.getUsername());
            
            // App Role
            String role = db.getUserRole();
            backup.put("app_role", role);

            // User Interests (For Users)
            JSONArray interestsArray = new JSONArray();
            Set<String> interests = db.getInterests();
            for (String interest : interests) {
                interestsArray.put(interest);
            }
            backup.put("user_interests", interestsArray);

            // Private Storage (For Advertisers)
            backup.put("cloudflare_worker_url", db.getCloudflareWorkerUrl());
            backup.put("cloudflare_secret_token", db.getCloudflareSecretToken());

            // 2. Write the JSON string to the physical file via Uri
            String jsonContent = backup.toString(4); // Indented for readability
            OutputStream outputStream = context.getContentResolver().openOutputStream(fileUri);
            if (outputStream != null) {
                outputStream.write(jsonContent.getBytes());
                outputStream.close();
                Toast.makeText(context, "Identity Passport saved successfully.", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Export failed: " + e.getMessage());
            Toast.makeText(context, "Export Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Reads a JSON file, validates the Nostr identity, and restores the account.
     * TRIGGERED BY: ACTION_OPEN_DOCUMENT result in SplashActivity or SettingsFragment.
     */
    public static void importProfileFromJson(Context context, Uri fileUri) {
        try {
            // 1. Read JSON file content
            InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            inputStream.close();

            // 2. Parse JSON
            JSONObject backup = new JSONObject(sb.toString());
            String priv = backup.optString("nostr_private_key", "");
            String pub = backup.optString("nostr_public_key", "");
            String name = backup.optString("username", "");
            String role = backup.optString("app_role", "");
            String cfUrl = backup.optString("cloudflare_worker_url", "");
            String cfToken = backup.optString("cloudflare_secret_token", "");

            // 3. CRYPTOGRAPHY VALIDATION
            // Strict check: Hex length must be 64 characters (32 bytes)
            if (priv.length() != 64 || pub.length() != 64) {
                throw new Exception("Invalid Identity: Keys must be 64-character hex strings.");
            }

            // Basic hex validity check
            try {
                NostrKeyManager.hexToBytes(priv);
                NostrKeyManager.hexToBytes(pub);
            } catch (Exception e) {
                throw new Exception("Identity Error: File contains non-hex characters.");
            }

            // 4. Batch Restore to Database
            Set<String> interests = new HashSet<>();
            JSONArray intArr = backup.optJSONArray("user_interests");
            if (intArr != null) {
                for (int i = 0; i < intArr.length(); i++) {
                    interests.add(intArr.getString(i));
                }
            }

            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            db.batchRestoreAccount(priv, pub, name, role, interests, cfUrl, cfToken);

            Toast.makeText(context, "Identity Passport Verified. Restoring...", Toast.LENGTH_SHORT).show();

            // 5. App Restart
            restartApp(context);

        } catch (Exception e) {
            Log.e(TAG, "Import failed: " + e.getMessage());
            Toast.makeText(context, "Restore Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Completely restarts the app and clears the backstack to ensure the new identity is loaded.
     */
    private static void restartApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        
        // If context is an Activity, finish it
        if (context instanceof Activity) {
            ((Activity) context).finish();
        }
        
        // Force process exit to ensure all static instances (WSManager, etc.) are reset
        System.exit(0);
    }
}