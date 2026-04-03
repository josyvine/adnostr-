package com.adnostr.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Local Data Management Utility for AdNostr.
 * Handles persistence for Cryptographic Keys, Roles, Relays, and User Interests.
 */
public class AdNostrDatabaseHelper {

    private static final String PREF_NAME = "adnostr_secure_prefs";

    // Identity Keys
    private static final String KEY_PRIVATE_KEY = "nostr_private_key_hex";
    private static final String KEY_PUBLIC_KEY = "nostr_public_key_hex";

    // App State & Role
    private static final String KEY_USER_ROLE = "user_app_role"; // "USER" or "ADVERTISER"
    private static final String KEY_SETUP_COMPLETE = "setup_complete_flag";

    // Network & Content
    private static final String KEY_RELAY_LIST = "nostr_relay_list_json";
    private static final String KEY_USER_INTERESTS = "ad_interest_hashtags";

    private static AdNostrDatabaseHelper instance;
    private final SharedPreferences prefs;

    private AdNostrDatabaseHelper(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Singleton accessor for the database helper.
     */
    public static synchronized AdNostrDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new AdNostrDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // =========================================================================
    // IDENTITY & CRYPTO STORAGE
    // =========================================================================

    public void savePrivateKey(String hexKey) {
        prefs.edit().putString(KEY_PRIVATE_KEY, hexKey).apply();
    }

    public String getPrivateKey() {
        return prefs.getString(KEY_PRIVATE_KEY, null);
    }

    public void savePublicKey(String hexKey) {
        prefs.edit().putString(KEY_PUBLIC_KEY, hexKey).apply();
    }

    public String getPublicKey() {
        return prefs.getString(KEY_PUBLIC_KEY, null);
    }

    // =========================================================================
    // ROLE & ONBOARDING STATE
    // =========================================================================

    public void saveUserRole(String role) {
        prefs.edit().putString(KEY_USER_ROLE, role).apply();
    }

    public String getUserRole() {
        return prefs.getString(KEY_USER_ROLE, null);
    }

    public void setSetupComplete(boolean status) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, status).apply();
    }

    public boolean isSetupComplete() {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false);
    }

    // =========================================================================
    // RELAY MANAGEMENT
    // =========================================================================

    /**
     * Saves the list of relays as a JSON string for persistence.
     */
    public void saveRelayList(String jsonRelays) {
        prefs.edit().putString(KEY_RELAY_LIST, jsonRelays).apply();
    }

    public String getRelayList() {
        return prefs.getString(KEY_RELAY_LIST, null);
    }

    // =========================================================================
    // USER INTERESTS (HASHTAGS)
    // =========================================================================

    /**
     * Saves the set of hashtags the user is interested in (e.g., #food, #kochi).
     */
    public void saveInterests(Set<String> interests) {
        prefs.edit().putStringSet(KEY_USER_INTERESTS, interests).apply();
    }

    public Set<String> getInterests() {
        return prefs.getStringSet(KEY_USER_INTERESTS, new HashSet<>());
    }

    /**
     * Helper to check if a specific interest is followed.
     */
    public boolean isInterestedIn(String hashtag) {
        Set<String> interests = getInterests();
        return interests.contains(hashtag.toLowerCase().replace("#", ""));
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    /**
     * Completely resets the app state.
     */
    public void clearAllData() {
        prefs.edit().clear().apply();
    }
}