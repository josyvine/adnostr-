package com.adnostr.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Local Data Management Utility for AdNostr.
 * UPDATED: Added support for dynamic custom hashtag lists and active listening states.
 */
public class AdNostrDatabaseHelper {

    private static final String PREF_NAME = "adnostr_secure_prefs";

    // Identity Keys
    private static final String KEY_PRIVATE_KEY = "nostr_private_key_hex";
    private static final String KEY_PUBLIC_KEY = "nostr_public_key_hex";

    // App State & Role
    private static final String KEY_USER_ROLE = "user_app_role"; // "USER" or "ADVERTISER"
    private static final String KEY_SETUP_COMPLETE = "setup_complete_flag";
    private static final String KEY_IS_LISTENING = "is_listening_for_ads";

    // Network & Content
    private static final String KEY_RELAY_LIST = "nostr_relay_list_json";
    private static final String KEY_USER_INTERESTS = "ad_interest_hashtags"; // Tags the user "follows"
    private static final String KEY_AVAILABLE_HASHTAGS = "available_hashtag_pool"; // Tags shown in the grid

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
    // LISTENING STATE (UI FEEDBACK)
    // =========================================================================

    public void setListeningState(boolean isListening) {
        prefs.edit().putBoolean(KEY_IS_LISTENING, isListening).apply();
    }

    public boolean isListening() {
        return prefs.getBoolean(KEY_IS_LISTENING, false);
    }

    // =========================================================================
    // HASHTAG MANAGEMENT (POOL)
    // =========================================================================

    /**
     * Saves the full pool of hashtags shown in the UI grid.
     */
    public void saveAvailableHashtags(Set<String> hashtagPool) {
        prefs.edit().putStringSet(KEY_AVAILABLE_HASHTAGS, hashtagPool).apply();
    }

    /**
     * Returns the pool of hashtags for the grid. If empty, returns default bootstrap tags.
     */
    public Set<String> getAvailableHashtags() {
        Set<String> defaults = new HashSet<>(Arrays.asList("food", "kochi", "electronics", "realestate", "cars", "fashion", "deals"));
        return prefs.getStringSet(KEY_AVAILABLE_HASHTAGS, defaults);
    }

    // =========================================================================
    // USER INTERESTS (FOLLOWED TAGS)
    // =========================================================================

    public void saveInterests(Set<String> interests) {
        prefs.edit().putStringSet(KEY_USER_INTERESTS, interests).apply();
    }

    public Set<String> getInterests() {
        return prefs.getStringSet(KEY_USER_INTERESTS, new HashSet<>());
    }

    public boolean isInterestedIn(String hashtag) {
        Set<String> interests = getInterests();
        return interests.contains(hashtag.toLowerCase().replace("#", ""));
    }

    // =========================================================================
    // RELAY MANAGEMENT
    // =========================================================================

    public void saveRelayList(String jsonRelays) {
        prefs.edit().putString(KEY_RELAY_LIST, jsonRelays).apply();
    }

    public String getRelayList() {
        return prefs.getString(KEY_RELAY_LIST, null);
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    public void clearAllData() {
        prefs.edit().clear().apply();
    }
}