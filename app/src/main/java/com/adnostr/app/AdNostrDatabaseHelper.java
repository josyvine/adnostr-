package com.adnostr.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Local Data Management Utility for AdNostr.
 * UPDATED: Added a massive bootstrap relay pool to ensure 30+ connections 
 * and fixed default hashtag visibility.
 * FIXED: Added Username saving capabilities for Reach Discovery identification.
 */
public class AdNostrDatabaseHelper {

    private static final String PREF_NAME = "adnostr_secure_prefs";

    // Identity Keys
    private static final String KEY_PRIVATE_KEY = "nostr_private_key_hex";
    private static final String KEY_PUBLIC_KEY = "nostr_public_key_hex";
    private static final String KEY_USERNAME = "user_display_name"; // NEW: For Reach Discovery

    // App State & Role
    private static final String KEY_USER_ROLE = "user_app_role"; 
    private static final String KEY_SETUP_COMPLETE = "setup_complete_flag";
    private static final String KEY_IS_LISTENING = "is_listening_for_ads";

    // Network & Content
    private static final String KEY_RELAY_LIST = "nostr_relay_list_json";
    private static final String KEY_USER_INTERESTS = "ad_interest_hashtags"; 
    private static final String KEY_AVAILABLE_HASHTAGS = "available_hashtag_pool";

    private static AdNostrDatabaseHelper instance;
    private final SharedPreferences prefs;

    // BOOTSTRAP RELAY LIST (20-50 high-traffic nodes for decentralized reach)
    private final String[] BOOTSTRAP_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://relay.snort.social",
            "wss://relay.primal.net",
            "wss://nostr.wine",
            "wss://offchain.pub",
            "wss://nostr.mom",
            "wss://relay.current.fyi",
            "wss://purplepag.es",
            "wss://relay.taxi",
            "wss://eden.nostr.land",
            "wss://relay.orangepill.dev",
            "wss://nostr.fmt.wiz.biz",
            "wss://nostr.bitcoiner.social",
            "wss://relay.nostr.com.au",
            "wss://nostr.blockstream.info",
            "wss://relay.nostrid.com",
            "wss://nostr.v0l.io",
            "wss://brb.io",
            "wss://atlas.nostr.land",
            "wss://bitcoiner.social",
            "wss://relay.noswhere.com",
            "wss://nostr.build",
            "wss://nostr.lu.ke",
            "wss://relay.nostr.bg",
            "wss://nostr.oxtr.dev",
            "wss://nostr.land",
            "wss://relay.minds.com/nostr/v1/ws",
            "wss://nostr-pub.wellorder.net",
            "wss://relay.urql.dev"
    };

    private AdNostrDatabaseHelper(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

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

    // NEW: Save and retrieve the optional username for reach discovery
    public void saveUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
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
    // LISTENING STATE
    // =========================================================================

    public void setListeningState(boolean isListening) {
        prefs.edit().putBoolean(KEY_IS_LISTENING, isListening).apply();
    }

    public boolean isListening() {
        return prefs.getBoolean(KEY_IS_LISTENING, false);
    }

    // =========================================================================
    // HASHTAG MANAGEMENT
    // =========================================================================

    public void saveAvailableHashtags(Set<String> hashtagPool) {
        prefs.edit().putStringSet(KEY_AVAILABLE_HASHTAGS, hashtagPool).apply();
    }

    public Set<String> getAvailableHashtags() {
        // Expanded default list to include your test cases
        Set<String> defaults = new HashSet<>(Arrays.asList(
                "food", "kochi", "electronics", "realestate", 
                "cars", "fashion", "deals", "shoes", "adnostr"
        ));
        return prefs.getStringSet(KEY_AVAILABLE_HASHTAGS, defaults);
    }

    public void saveInterests(Set<String> interests) {
        prefs.edit().putStringSet(KEY_USER_INTERESTS, interests).apply();
    }

    public Set<String> getInterests() {
        return prefs.getStringSet(KEY_USER_INTERESTS, new HashSet<>());
    }

    // =========================================================================
    // RELAY MANAGEMENT
    // =========================================================================

    /**
     * Returns the list of relays. 
     * FIXED: If no custom list is found, it returns the full 31+ bootstrap relays.
     */
    public Set<String> getRelayPool() {
        String savedJson = prefs.getString(KEY_RELAY_LIST, null);
        if (savedJson == null) {
            // Return all bootstrap relays as a Set
            return new HashSet<>(Arrays.asList(BOOTSTRAP_RELAYS));
        }
        // In full implementation, parse savedJson here.
        return new HashSet<>(Arrays.asList(BOOTSTRAP_RELAYS));
    }

    public void saveRelayList(String jsonRelays) {
        prefs.edit().putString(KEY_RELAY_LIST, jsonRelays).apply();
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    public void clearAllData() {
        prefs.edit().clear().apply();
    }
}