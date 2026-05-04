package com.adnostr.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * ENHANCEMENT: Added Privacy Command Center keys for Username visibility and Live Location.
 * RETAINED: All Nostr identity, Relay pool, History, and Hashtag logic.
 * FIXED: Changed from .apply() to .commit() to ensure JSON restoration sticks on restart.
 * FIXED: Role-based key separation for Interests and Privacy to prevent data bleeding between roles.
 * FIXED FOR POPUP: Implemented Fallback logic in getInterests to prevent empty subscriptions.
 * NEW: Added KEY_WIPED_SCHEMA_IDS and KEY_HIDDEN_HARDCODED for Deletion Persistence.
 * ENHANCEMENT: Added KEY_CONSOLE_LOG_ENABLED and KEY_DEBUG_MODE_ACTIVE for forensic management.
 * 
 * CROWDSOURCED DATA FIX:
 * - KEY_SCHEMA_CACHE_JSON: Permanent local memory to prevent crowdsourced data from vanishing after 12 hours.
 * - KEY_DISMISSED_REPORT_IDS: Local storage to hide Admin report cards without network-wide deletion.
 * 
 * COLLECTIVE MEMORY UPDATE:
 * - saveSchemaCache: Implements an Integrity Gate to prevent empty network responses from wiping local data.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Hardcoded master ADMIN_PUBKEY from verified identity passport.
 * - Integrated KEY_REPORT_LAST_SEEN for Forensic Badge counter synchronization.
 * 
 * VOLATILITY & SEQUENTIAL HEALING FIX:
 * - KEY_FORENSIC_ARCHIVE_JSON: The "Immutable Source of Truth". Unlike the cache, this ONLY grows.
 * - saveToForensicArchive: Hard-locks every seen category/spec/brand into local permanent storage.
 * - REPAIR UPDATE: getForensicArchive now filters results against the Wiped blocklist for integrity.
 * - DUPLICATE GATEKEEPER: Added content-aware de-duplication to prevent archive bloat and relay spam rejections.
 */
public class AdNostrDatabaseHelper {

    private static final String PREF_NAME = "adnostr_secure_prefs";

    // =========================================================================
    // ADMIN SUPREMACY CONFIGURATION
    // =========================================================================
    // MASTER IDENTITY: The cryptographic root of trust for AdNostr governance.
    public static final String ADMIN_PUBKEY = "ab9e5584a7c2732d7265ed4bf1c101939b1d408891478fc0715b29e961760662";

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

    // SCHEMA PURGE PERSISTENCE
    private static final String KEY_WIPED_SCHEMA_IDS = "wiped_schema_event_ids";
    private static final String KEY_HIDDEN_HARDCODED = "globally_hidden_hardcoded_names";

    // CROWDSOURCED MEMORY ENGINE (NEW)
    private static final String KEY_SCHEMA_CACHE_JSON = "marketplace_schema_cache_v2";
    private static final String KEY_DISMISSED_REPORT_IDS = "admin_dismissed_report_events";

    // ADMIN FORENSIC LOGS
    private static final String KEY_REPORT_LAST_SEEN = "admin_report_last_seen_timestamp";

    // HASHTAG REGISTRY (NEW)
    private static final String KEY_OWNED_HASHTAGS = "my_owned_hashtags_registry";

    // PRIVACY COMMAND CENTER KEYS
    private static final String KEY_USERNAME_HIDDEN = "privacy_username_hidden";
    private static final String KEY_LIVE_LOCATION_ENABLED = "privacy_live_location_enabled";

    // CONSOLE MANAGEMENT KEYS (NEW)
    private static final String KEY_CONSOLE_LOG_ENABLED = "system_console_log_enabled";
    private static final String KEY_DEBUG_MODE_ACTIVE = "system_debug_mode_active";

    // VOLATILITY FIX: Master Forensic Archive Key
    private static final String KEY_FORENSIC_ARCHIVE_JSON = "forensic_permanent_archive_master";

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
    // VOLATILITY FIX: MASTER IMMUTABLE ARCHIVE METHODS
    // =========================================================================

    /**
     * Logic: Permanent Hard-Locking of crowdsourced data.
     * Unlike the "Schema Cache" which updates based on the network, this Archive 
     * never removes an item. It serves as the primary source for Sequential Re-publishing.
     * REPAIR UPDATE: Rejects events with empty content to prevent archive corruption.
     * DUPLICATE GATEKEEPER: Now parses content to ensure unique metadata entries.
     */
    public synchronized void saveToForensicArchive(String eventJson) {
        try {
            JSONObject newEvent = new JSONObject(eventJson);
            String newId = newEvent.getString("id");
            int kind = newEvent.optInt("kind", -1);
            String contentStr = newEvent.optString("content", "");

            // GATEKEEPER 1: Prevent "End of input" crashes by rejecting empty content frames
            if (contentStr.isEmpty()) {
                android.util.Log.w("AdNostr_Archive", "REJECTED: Attempted to save empty content frame to archive.");
                return;
            }

            String currentArchive = prefs.getString(KEY_FORENSIC_ARCHIVE_JSON, "[]");
            JSONArray archiveArray = new JSONArray(currentArchive);

            // GATEKEEPER 2: Deep Content De-duplication (Kind 30006/30007)
            // This prevents saving the same "Bajaj" model 50 times from different relays.
            if (kind == 30006 || kind == 30007) {
                JSONObject newContent = new JSONObject(contentStr);
                String newType = newContent.optString("type", "");
                String newSub = newContent.optString("sub", "");
                String newCat = newContent.optString("category", "");
                String newLabel = newContent.optString("label", "");

                for (int i = 0; i < archiveArray.length(); i++) {
                    JSONObject existingEvent = archiveArray.getJSONObject(i);
                    // Match unique ID first for speed
                    if (existingEvent.getString("id").equals(newId)) return;

                    // Match Content logic for Schema Persistence
                    if (existingEvent.optInt("kind") == kind) {
                        JSONObject existingContent = new JSONObject(existingEvent.getString("content"));
                        
                        // Case A: Duplicate Category
                        if (kind == 30006 && "category".equals(newType)) {
                            if (existingContent.optString("sub").equalsIgnoreCase(newSub)) return;
                        }
                        // Case B: Duplicate Technical Field
                        else if (kind == 30006 && "field".equals(newType)) {
                            if (existingContent.optString("category").equalsIgnoreCase(newCat) && 
                                existingContent.optString("label").equalsIgnoreCase(newLabel)) return;
                        }
                        // Case C: Duplicate Brand/Value Pool
                        else if (kind == 30007) {
                            // Match by category and the keys within 'specs' (e.g., brand name)
                            if (existingContent.optString("category").equalsIgnoreCase(newCat)) {
                                JSONObject existingSpecs = existingContent.optJSONObject("specs");
                                JSONObject newSpecs = newContent.optJSONObject("specs");
                                if (existingSpecs != null && newSpecs != null && existingSpecs.toString().equals(newSpecs.toString())) return;
                            }
                        }
                    }
                }
            } else {
                // Standard de-duplication for non-schema events
                for (int i = 0; i < archiveArray.length(); i++) {
                    if (archiveArray.getJSONObject(i).getString("id").equals(newId)) return;
                }
            }

            archiveArray.put(newEvent);
            // Synchronous commit to ensure data is locked to disk immediately
            prefs.edit().putString(KEY_FORENSIC_ARCHIVE_JSON, archiveArray.toString()).commit();
            android.util.Log.i("AdNostr_Archive", "New metadata frame hard-locked to Forensic Archive. Total Frames: " + archiveArray.length());
            
        } catch (Exception e) {
            android.util.Log.e("AdNostr_Archive", "Failed to append to permanent archive: " + e.getMessage());
        }
    }

    /**
     * Retrieves the entire permanent truth table for network healing.
     * REPAIR UPDATE: Cross-references results with the wiped schema blocklist 
     * to ensure deleted data stays deleted.
     */
    public String getForensicArchive() {
        try {
            String rawArchive = prefs.getString(KEY_FORENSIC_ARCHIVE_JSON, "[]");

            // CRITICAL REPAIR: Sanitize empty strings to prevent "End of input at character 0" error
            if (rawArchive == null || rawArchive.trim().isEmpty()) {
                android.util.Log.e("AdNostr_Archive", "SYSTEM: Local archive key found but is empty string.");
                rawArchive = "[]";
            }

            Set<String> wipedIds = prefs.getStringSet(KEY_WIPED_SCHEMA_IDS, new HashSet<>());

            // Optimization: If nothing is wiped, return the sanitized raw string instantly
            if (wipedIds.isEmpty()) return rawArchive;

            JSONArray originalArray = new JSONArray(rawArchive);
            JSONArray filteredArray = new JSONArray();

            for (int i = 0; i < originalArray.length(); i++) {
                JSONObject event = originalArray.getJSONObject(i);
                String id = event.optString("id", "");

                // ARCHIVE INTEGRITY GATE: Filter out IDs in the permanent blocklist
                if (!wipedIds.contains(id)) {
                    filteredArray.put(event);
                }
            }

            return filteredArray.toString();

        } catch (Exception e) {
            android.util.Log.e("AdNostr_Archive", "Archive Integrity Check Failed: " + e.getMessage());
            return "[]"; // Safety fallback
        }
    }

    // =========================================================================
    // ADMIN SUPREMACY HELPERS
    // =========================================================================

    /**
     * Supreme Authority Check: Compares active user's pubkey against hardcoded master ID.
     */
    public boolean isAdmin() {
        String currentPub = getPublicKey();
        if (currentPub == null) return false;
        return currentPub.equalsIgnoreCase(ADMIN_PUBKEY);
    }

    /**
     * Records current timestamp when the admin exits the ReportActivity.
     */
    public void saveReportLastSeen() {
        long now = System.currentTimeMillis() / 1000;
        prefs.edit().putLong(KEY_REPORT_LAST_SEEN, now).apply();
    }

    public long getReportLastSeen() {
        return prefs.getLong(KEY_REPORT_LAST_SEEN, 0);
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
    // BRANDING STORAGE
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
        // FIXED: Return "USER" if role is not set to prevent key-bleeding
        String role = prefs.getString(KEY_USER_ROLE, "");
        return (role == null || role.isEmpty()) ? "USER" : role;
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
    // HASHTAG MANAGEMENT (FIXED FOR ROLE COLLISION)
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

    /**
     * FIXED: Appends the current role to the key to prevent bleeding between User/Advertiser interests.
     */
    public void saveInterests(Set<String> interests) {
        String role = getUserRole();
        prefs.edit().putStringSet(KEY_USER_INTERESTS + "_" + role, interests).apply();
    }

    /**
     * FIXED FOR POPUP: Retrieves interests based on the active role with Legacy Fallback.
     */
    public Set<String> getInterests() {
        String role = getUserRole();
        Set<String> roleSpecific = prefs.getStringSet(KEY_USER_INTERESTS + "_" + role, new HashSet<>());

        // LEGACY FALLBACK: If role-specific is empty, check the first-zip base key
        if (roleSpecific.isEmpty()) {
            Set<String> legacy = prefs.getStringSet(KEY_USER_INTERESTS, new HashSet<>());
            if (!legacy.isEmpty()) {
                // Migrate to new role-specific storage instantly
                saveInterests(legacy);
                return legacy;
            }
        }
        return roleSpecific;
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
    // SCHEMA PURGE PERSISTENCE
    // =========================================================================

    /**
     * Records a schema event ID (Category/Field) as permanently wiped.
     */
    public void addWipedSchemaId(String eventId) {
        Set<String> wiped = new HashSet<>(prefs.getStringSet(KEY_WIPED_SCHEMA_IDS, new HashSet<>()));
        wiped.add(eventId);
        prefs.edit().putStringSet(KEY_WIPED_SCHEMA_IDS, wiped).apply();
    }

    public boolean isSchemaWiped(String eventId) {
        Set<String> wiped = prefs.getStringSet(KEY_WIPED_SCHEMA_IDS, new HashSet<>());
        return wiped.contains(eventId);
    }

    /**
     * Globally hides a hardcoded category name (e.g. "Cars").
     */
    public void addHiddenHardcodedName(String name) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet(KEY_HIDDEN_HARDCODED, new HashSet<>()));
        hidden.add(name);
        prefs.edit().putStringSet(KEY_HIDDEN_HARDCODED, hidden).apply();
    }

    public Set<String> getHiddenHardcodedNames() {
        return prefs.getStringSet(KEY_HIDDEN_HARDCODED, new HashSet<>());
    }

    // =========================================================================
    // CROWDSOURCED MEMORY METHODS (COLLECTIVE MEMORY UPDATE)
    // =========================================================================

    /**
     * Permanent Memory: Saves the entire synchronized Marketplace Schema locally.
     * COLLECTIVE MEMORY FIX: Implements an Integrity Gate. 
     * If the incoming JSON is empty/small but local memory is full, the save is rejected.
     * This prevents slow relays or network amnesia from wiping your Bajaj data.
     */
    public void saveSchemaCache(String json) {
        // 1. Density Check: Is the incoming data empty?
        if (json == null || json.equals("{}") || json.isEmpty() || json.length() < 10) {
            String existing = getSchemaCache();
            // If we already have real data, don't allow an empty overwrite
            if (existing != null && existing.length() > 50) {
                return; 
            }
        }

        // 2. Data Loss Threshold: Is the new JSON suspiciously smaller than the old?
        String existing = getSchemaCache();
        if (existing != null && existing.length() > 200 && json != null) {
            // If we lose more than 70% of data in one sync, something is wrong with the relays.
            if (json.length() < (existing.length() * 0.3)) {
                return;
            }
        }

        // Use commit() for synchronous hard-locking to the disk
        prefs.edit().putString(KEY_SCHEMA_CACHE_JSON, json).commit();
    }

    /**
     * Permanent Memory: Loads the last known successful network consensus instantly.
     */
    public String getSchemaCache() {
        return prefs.getString(KEY_SCHEMA_CACHE_JSON, "{}");
    }

    /**
     * Admin Control: Records a report card ID as dismissed so it doesn't show in 
     * the forensic console locally, without deleting it for everyone else.
     */
    public void addDismissedReportId(String eventId) {
        Set<String> dismissed = new HashSet<>(prefs.getStringSet(KEY_DISMISSED_REPORT_IDS, new HashSet<>()));
        dismissed.add(eventId);
        prefs.edit().putStringSet(KEY_DISMISSED_REPORT_IDS, dismissed).apply();
    }

    public boolean isReportDismissed(String eventId) {
        Set<String> dismissed = prefs.getStringSet(KEY_DISMISSED_REPORT_IDS, new HashSet<>());
        return dismissed.contains(eventId);
    }

    // =========================================================================
    // OWNED HASHTAG REGISTRY
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
    // PRIVACY COMMAND CENTER METHODS
    // =========================================================================

    /**
     * FIXED: Appends the current role to ensure privacy settings are independent.
     */
    public void setUsernameHidden(boolean isHidden) {
        String role = getUserRole();
        prefs.edit().putBoolean(KEY_USERNAME_HIDDEN + "_" + role, isHidden).apply();
    }

    /**
     * FIXED: Retrieves privacy state for the active role.
     */
    public boolean isUsernameHidden() {
        String role = getUserRole();
        return prefs.getBoolean(KEY_USERNAME_HIDDEN + "_" + role, false);
    }

    public void setLiveLocationEnabled(boolean isEnabled) {
        String role = getUserRole();
        prefs.edit().putBoolean(KEY_LIVE_LOCATION_ENABLED + "_" + role, isEnabled).apply();
    }

    /**
     * FIXED: Retrieves live location state for the active role.
     */
    public boolean isLiveLocationEnabled() {
        String role = getUserRole();
        return prefs.getBoolean(KEY_LIVE_LOCATION_ENABLED + "_" + role, false);
    }

    // =========================================================================
    // CONSOLE & DEBUG MANAGEMENT METHODS
    // =========================================================================

    /**
     * Logic: Master switch for forensic logging.
     * Uses commit() for synchronous consistency across threads.
     */
    public void setConsoleLogEnabled(boolean isEnabled) {
        prefs.edit().putBoolean(KEY_CONSOLE_LOG_ENABLED, isEnabled).commit();
    }

    public boolean isConsoleLogEnabled() {
        // Default to TRUE so user can see initial network status
        return prefs.getBoolean(KEY_CONSOLE_LOG_ENABLED, true);
    }

    /**
     * Logic: Toggle between Professional summaries and raw JSON protocol frames.
     */
    public void setDebugModeActive(boolean isActive) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE_ACTIVE, isActive).commit();
    }

    public boolean isDebugModeActive() {
        // Default to FALSE for a professional experience
        return prefs.getBoolean(KEY_DEBUG_MODE_ACTIVE, false);
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

        // 3. Restore Interests (For Users) - Uses role-aware key logic
        if (interests != null) editor.putStringSet(KEY_USER_INTERESTS + "_" + role, interests);

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