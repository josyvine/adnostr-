package com.adnostr.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local Data Management Utility for AdNostr.
 * UPDATED: Added keys for Private Cloudflare R2 Storage (Worker URL & Secret Token).
 * UPDATED: Added KEY_WIPED_AD_IDS to prevent "Phantom Ad" re-notifications.
 * UPDATED: Added KEY_OWNED_HASHTAGS to manage claimed private hashtags (Hybrid Registry).
 * UPDATED: Added Advertiser Logo URL and ID keys for Branding/UI overhaul.
 * ENHANCEMENT: Added batchRestoreAccount for JSON Identity Portability.
 * RETAINED: All Nostr identity, Relay pool, History, and Hashtag logic.
 * FIXED: Changed from .apply() to .commit() to ensure JSON restoration sticks on restart.
 */
public class AdNostrDatabaseHelper {

    private static final String PREF_NAME = "adnostr_secure_prefs";

    // Identity Keys
    private static final String KEY_PRIVATE_KEY = "nostr_private_key_hex";
    private static final String KEY_PUBLIC_KEY = "nostr_public_key_hex";
    private static final String KEY_USERNAME = "user_display_name";

    // BRANDING
    private static final String KEY_ADVERTISER_LOGO_URL = "advertiser_logo_url";
    private static final String KEY_ADVERTISER_LOGO_ID = "advertiser_logo_id";

    // App State & Role
    private static final String KEY_USER_ROLE = "user_app_role"; 
    private static final String KEY_SETUP_COMPLETE = "setup_complete_flag";
    private static final String KEY_IS_LISTENING = "is_listening_for_ads";

    // Network & Content
    private static final String KEY_RELAY_LIST = "nostr_relay_list_json";
    private static final String KEY_USER_INTERESTS = "ad_interest_hashtags"; 
    private static final String KEY_AVAILABLE_HASHTAGS = "available_hashtag_pool";

    // PRIVATE CLOUDFLARE R2 STORAGE
    private static final String KEY_CLOUDFLARE_WORKER_URL = "cf_worker_api_url";
    private static final String KEY_CLOUDFLARE_SECRET_TOKEN = "cf_secret_auth_token";

    // Media Deletion Mapping (Stores Cloudflare File IDs against Nostr Event IDs)
    private static final String KEY_ADVERTISER_DELETION_MAP = "local_deletion_urls_map";

    // History Keys
    private static final String KEY_USER_HISTORY = "local_user_ad_history";
    private static final String KEY_ADVERTISER_HISTORY = "local_advertiser_ad_history";

    // PHANTOM AD PREVENTION
    private static final String KEY_WIPED_AD_IDS = "wiped_deleted_ad_ids";

    // HASHTAG REGISTRY (NEW)
    private static final String KEY_OWNED_HASHTAGS = "my_owned_hashtags_registry";

    private static AdNostrDatabaseHelper instance;
    private final SharedPreferences prefs;

    // BOOTSTRAP RELAY LIST
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

    public void saveUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    // =========================================================================
    // BRANDING STORAGE (NEW)
    // =========================================================================

    public void saveAdvertiserLogoUrl(String url) {
        prefs.edit().putString(KEY_ADVERTISER_LOGO_URL, url).apply();
    }

    public String getAdvertiserLogoUrl() {
        return prefs.getString(KEY_ADVERTISER_LOGO_URL, "");
    }

    public void saveAdvertiserLogoId(String id) {
        prefs.edit().putString(KEY_ADVERTISER_LOGO_ID, id).apply();
    }

    public String getAdvertiserLogoId() {
        return prefs.getString(KEY_ADVERTISER_LOGO_ID, "");
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

    public Set<String> getRelayPool() {
        return new HashSet<>(Arrays.asList(BOOTSTRAP_RELAYS));
    }

    public void saveRelayList(String jsonRelays) {
        prefs.edit().putString(KEY_RELAY_LIST, jsonRelays).apply();
    }

    // =========================================================================
    // CLOUDFLARE R2 PRIVATE STORAGE SETTINGS
    // =========================================================================

    public void saveCloudflareWorkerUrl(String url) {
        prefs.edit().putString(KEY_CLOUDFLARE_WORKER_URL, url).apply();
    }

    public String getCloudflareWorkerUrl() {
        return prefs.getString(KEY_CLOUDFLARE_WORKER_URL, "");
    }

    public void saveCloudflareSecretToken(String token) {
        prefs.edit().putString(KEY_CLOUDFLARE_SECRET_TOKEN, token).apply();
    }

    public String getCloudflareSecretToken() {
        return prefs.getString(KEY_CLOUDFLARE_SECRET_TOKEN, "");
    }

    public void saveDeletionData(String eventId, String deletionUrl) {
        prefs.edit().putString(KEY_ADVERTISER_DELETION_MAP + "_" + eventId, deletionUrl).apply();
    }

    public String getDeletionData(String eventId) {
        return prefs.getString(KEY_ADVERTISER_DELETION_MAP + "_" + eventId, null);
    }

    public void removeDeletionData(String eventId) {
        prefs.edit().remove(KEY_ADVERTISER_DELETION_MAP + "_" + eventId).apply();
    }

    // =========================================================================
    // AD HISTORY & PHANTOM PREVENTION
    // =========================================================================

    public void saveToUserHistory(String eventJson) {
        Set<String> history = new HashSet<>(prefs.getStringSet(KEY_USER_HISTORY, new HashSet<>()));
        history.add(eventJson);
        prefs.edit().putStringSet(KEY_USER_HISTORY, history).apply();
    }

    public Set<String> getUserHistory() {
        return prefs.getStringSet(KEY_USER_HISTORY, new HashSet<>());
    }

    public void deleteFromUserHistory(String eventJson) {
        Set<String> history = new HashSet<>(prefs.getStringSet(KEY_USER_HISTORY, new HashSet<>()));
        history.remove(eventJson);
        prefs.edit().putStringSet(KEY_USER_HISTORY, history).apply();
    }

    public void saveToAdvertiserHistory(String eventJson) {
        Set<String> history = new HashSet<>(prefs.getStringSet(KEY_ADVERTISER_HISTORY, new HashSet<>()));
        history.add(eventJson);
        prefs.edit().putStringSet(KEY_ADVERTISER_HISTORY, history).apply();
    }

    public Set<String> getAdvertiserHistory() {
        return prefs.getStringSet(KEY_ADVERTISER_HISTORY, new HashSet<>());
    }

    public void deleteFromAdvertiserHistory(String eventJson) {
        Set<String> history = new HashSet<>(prefs.getStringSet(KEY_ADVERTISER_HISTORY, new HashSet<>()));
        history.remove(eventJson);
        prefs.edit().putStringSet(KEY_ADVERTISER_HISTORY, history).apply();
    }

    /**
     * Records an ad ID as permanently wiped. Ensures phantom notifications never show.
     */
    public void addWipedAdId(String eventId) {
        Set<String> wiped = new HashSet<>(prefs.getStringSet(KEY_WIPED_AD_IDS, new HashSet<>()));
        wiped.add(eventId);
        prefs.edit().putStringSet(KEY_WIPED_AD_IDS, wiped).apply();
    }

    /**
     * Checks if an ad ID has been previously deleted or wiped.
     */
    public boolean isAdWiped(String eventId) {
        Set<String> wiped = prefs.getStringSet(KEY_WIPED_AD_IDS, new HashSet<>());
        return wiped.contains(eventId);
    }

    // =========================================================================
    // OWNED HASHTAG REGISTRY (NEW)
    // =========================================================================

    /**
     * Saves a full set of owned private hashtags for the advertiser.
     */
    public void saveOwnedHashtags(Set<String> hashtags) {
        prefs.edit().putStringSet(KEY_OWNED_HASHTAGS, hashtags).apply();
    }

    /**
     * Retrieves the set of private hashtags owned by the advertiser.
     */
    public Set<String> getOwnedHashtags() {
        return prefs.getStringSet(KEY_OWNED_HASHTAGS, new HashSet<>());
    }

    /**
     * Adds a single newly claimed hashtag to the local registry.
     */
    public void addOwnedHashtag(String tag) {
        Set<String> owned = new HashSet<>(getOwnedHashtags());
        owned.add(tag.toLowerCase().replace("#", ""));
        saveOwnedHashtags(owned);
    }

    /**
     * Removes a hashtag from the local registry if the deed is released.
     */
    public void removeOwnedHashtag(String tag) {
        Set<String> owned = new HashSet<>(getOwnedHashtags());
        owned.remove(tag.toLowerCase().replace("#", ""));
        saveOwnedHashtags(owned);
    }

    // =========================================================================
    // IDENTITY PORTABILITY: BATCH RESTORE (NEW)
    // =========================================================================

    /**
     * Overwrites all account info in one atomic transaction.
     * Used by the BackupManager when importing a Digital Passport JSON.
     * FIXED: Changed to .commit() to ensure synchronous write before app restart.
     */
    public void batchRestoreAccount(String privKey, String pubKey, String username, String role, Set<String> interests, String cfUrl, String cfToken) {
        SharedPreferences.Editor editor = prefs.edit();

        // 1. Restore Identity
        if (privKey != null && !privKey.isEmpty()) editor.putString(KEY_PRIVATE_KEY, privKey);
        if (pubKey != null && !pubKey.isEmpty()) editor.putString(KEY_PUBLIC_KEY, pubKey);
        if (username != null) editor.putString(KEY_USERNAME, username);

        // 2. Restore Role
        if (role != null && !role.isEmpty()) editor.putString(KEY_USER_ROLE, role);

        // 3. Restore Interests (For Users)
        if (interests != null) editor.putStringSet(KEY_USER_INTERESTS, interests);

        // 4. Restore Private Cloudflare Credentials (For Advertisers)
        if (cfUrl != null) editor.putString(KEY_CLOUDFLARE_WORKER_URL, cfUrl);
        if (cfToken != null) editor.putString(KEY_CLOUDFLARE_SECRET_TOKEN, cfToken);

        // 5. Enforce Setup Completion
        editor.putBoolean(KEY_SETUP_COMPLETE, true);

        // CRITICAL FIX: Synchronous commit forces write before System.exit()
        editor.commit();
    }

    public void clearAllData() {
        // CRITICAL FIX: Synchronous commit forces wipe before BackupManager continues
        prefs.edit().clear().commit();
    }
}