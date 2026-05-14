package com.adnostr.app;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemSettingsIconBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the Command Center Icon Grid.
 * FEATURE: Renders Instagram-style rounded square icons.
 * FEATURE: Role-based filtering (Hides Cloudflare and Registry for standard Users).
 * FEATURE: Unified click interface for Command Popups.
 * ENHANCEMENT: Added My Hashtags Registry icon for Advertisers.
 * ENHANCEMENT: Added Backup Identity icon for JSON Portability.
 * ENHANCEMENT: Added Privacy Command Center icon for all roles (Feature 1).
 * 
 * UPGRADE: Integrated Professional Material Icons for all Command functions.
 * ENHANCEMENT: Added CMD_CONSOLE_TUNE to manage Technical Console visibility and verbosity.
 * 
 * ADMIN SUPREMACY UPDATE:
 * - Forensic Access: Unlocks the "Report" icon in the Command Center for Admin identity.
 * - Command Type: Added CMD_REPORT (12) to the grid hierarchy.
 * 
 * 4-TIER HIERARCHY UPDATE:
 * - Memory Archive: Added CMD_ARCHIVE (13) for Advertiser B to anchor the network.
 * 
 * NEW ENHANCEMENT: CLOUDFLARE DATABASE ARCHITECT (STEP 2)
 * - Added CMD_CLOUDFLARE_DB (14) for Admin-only JSON Batch Uploading and Seeding.
 * - Icon strictly guarded by isAdmin security check.
 * 
 * THEME ENGINE UPDATE:
 * - Added CMD_THEME (15) to allow global toggling between Day and Night modes.
 * - Icon is universal and available to all roles.
 * 
 * TOTAL SURVEILLANCE UPDATE:
 * - Performance Tracking: Measures binding duration for each icon to detect scroll bloat.
 * - Interaction Surveillance: Logs the specific title and ID of every command clicked.
 */
public class SettingsIconAdapter extends RecyclerView.Adapter<SettingsIconAdapter.IconViewHolder> {

    // Unique Identifiers for Command Actions
    public static final int CMD_PROFILE = 1;
    public static final int CMD_MODE_SWITCH = 2;
    public static final int CMD_CLOUDFLARE = 3;
    public static final int CMD_HISTORY = 4;
    public static final int CMD_RESET = 5;

    // Registry Management Identifier
    public static final int CMD_MY_HASHTAGS = 6;

    // NEW: Identity Portability Identifier
    public static final int CMD_BACKUP = 7;

    // FEATURE 1: Privacy Command Identifier
    public static final int CMD_PRIVACY = 8;

    // GLITCH 1 & 2 FIX: New Command Identifiers
    public static final int CMD_PERSONALIZED = 9;
    public static final int CMD_BROWSE = 10;

    // CONSOLE MANAGEMENT
    public static final int CMD_CONSOLE_TUNE = 11;

    // ADMIN SUPREMACY: Report Identifier
    public static final int CMD_REPORT = 12;

    // 4-TIER UPDATE: Archive Identifier for Advertiser B
    public static final int CMD_ARCHIVE = 13;

    // NEW: CLOUDFLARE DATABASE ARCHITECT IDENTIFIER
    public static final int CMD_CLOUDFLARE_DB = 14;

    // NEW: THEME SWITCHER IDENTIFIER
    public static final int CMD_THEME = 15;

    private final List<SettingItem> items = new ArrayList<>();
    private final OnSettingClickListener listener;

    /**
     * Constructor filters the icon list based on the User's Role and Admin Status.
     * UPDATED: Implements conditional logic for Admin Moderator Console vs B Archive.
     * UPDATED: Injects the Database Architect icon for Admin verified sessions.
     * UPDATED: Injects the Theme icon for all users.
     */
    public SettingsIconAdapter(String userRole, boolean isAdmin, OnSettingClickListener listener) {
        this.listener = listener;

        // 1. Profile / Username Icon (Available for Users only)
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            items.add(new SettingItem(CMD_PROFILE, "Profile", R.drawable.ic_cmd_profile));
        }

        // 2. Mode Switch Icon (Available to Everyone)
        items.add(new SettingItem(CMD_MODE_SWITCH, "Switch Mode", R.drawable.ic_cmd_switch));

        // 3. FEATURE 1: Privacy Command Center (Available to Everyone)
        items.add(new SettingItem(CMD_PRIVACY, "Privacy", R.drawable.ic_cmd_privacy));

        // 4. THEME SWITCHER (Available to Everyone)
        items.add(new SettingItem(CMD_THEME, "Theme", R.drawable.ic_nav_report)); // Temporary icon, will be replaced by ic_cmd_theme.xml

        // GLITCH 1 FIX: Personalized Icon (Available to Everyone)
        items.add(new SettingItem(CMD_PERSONALIZED, "Personalized", R.drawable.ic_cmd_personalized));

        // GLITCH 2 FIX: Browse Icon (Available to Users)
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            items.add(new SettingItem(CMD_BROWSE, "Browse", R.drawable.ic_cmd_browse));
        }

        // 5. CLOUDFLARE STORAGE (Strict: Advertiser Only)
        if (RoleSelectionActivity.ROLE_ADVERTISER.equals(userRole)) {
            items.add(new SettingItem(CMD_CLOUDFLARE, "Storage", R.drawable.ic_cmd_storage));

            // 6. MY HASHTAGS REGISTRY (Strict: Advertiser Only)
            items.add(new SettingItem(CMD_MY_HASHTAGS, "Registry", R.drawable.ic_cmd_registry));
        }

        // 7. History Shortcut (Everyone)
        items.add(new SettingItem(CMD_HISTORY, "History", R.drawable.ic_cmd_history));

        // 8. NEW: Identity Backup (Available to Everyone)
        items.add(new SettingItem(CMD_BACKUP, "Backup", R.drawable.ic_cmd_backup));

        // 9. CONSOLE MANAGEMENT (Available to Everyone)
        items.add(new SettingItem(CMD_CONSOLE_TUNE, "Console", R.drawable.tune_24px));

        // 10. THE TRUTH ANCHOR LOGIC
        if (isAdmin) {
            // Admin receives full Moderator privileges
            items.add(new SettingItem(CMD_REPORT, "Report", R.drawable.ic_nav_report));
            
            // NEW: ADMIN-ONLY DATABASE ARCHITECT ICON
            items.add(new SettingItem(CMD_CLOUDFLARE_DB, "Architect", R.drawable.database_search_24px));
        } else if (RoleSelectionActivity.ROLE_ADVERTISER.equals(userRole)) {
            // Advertiser B receives a read-only Memory Archive to anchor the network
            items.add(new SettingItem(CMD_ARCHIVE, "Archive", R.drawable.ic_nav_report));
        }

        // 11. System Reset (Everyone)
        items.add(new SettingItem(CMD_RESET, "Reset App", R.drawable.ic_cmd_reset));

        // --- SURVEILLANCE: Log Adapter Initialization ---
        ActionReportLogger.logAction("COMMAND_GRID_INIT", "Populated grid with " + items.size() + " commands for role: " + userRole + " (Admin: " + isAdmin + ")");
    }

    @NonNull
    @Override
    public IconViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSettingsIconBinding binding = ItemSettingsIconBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new IconViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull IconViewHolder holder, int position) {
        // --- PERFORMANCE SURVEILLANCE ---
        long startTime = System.currentTimeMillis();
        
        SettingItem item = items.get(position);
        holder.bind(item, listener);

        long duration = System.currentTimeMillis() - startTime;
        // Log individual bind times to detect UI Bloat while scrolling the command center
        ActionReportLogger.logAction("ICON_RENDER", "Rendering: " + item.title + " (Index: " + position + "). Bind time: " + duration + "ms");
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * ViewHolder for the Command Center Icons.
     */
    static class IconViewHolder extends RecyclerView.ViewHolder {
        private final ItemSettingsIconBinding binding;

        public IconViewHolder(ItemSettingsIconBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(SettingItem item, OnSettingClickListener listener) {
            binding.tvSettingLabel.setText(item.title);
            binding.ivSettingIcon.setImageResource(item.iconRes);

            // Trigger the Command Popup logic in the Fragment
            binding.cvSettingIcon.setOnClickListener(v -> {
                // --- SURVEILLANCE: Log Physical Click ---
                ActionReportLogger.logAction("ICON_CLICK", "User tapped command: " + item.title + " (Type: " + item.commandType + ")");
                
                if (listener != null) {
                    listener.onSettingClicked(item.commandType);
                }
            });
        }
    }

    /**
     * Simple Data Model for a Command Icon.
     */
    private static class SettingItem {
        int commandType;
        String title;
        int iconRes;

        SettingItem(int type, String title, int res) {
            this.commandType = type;
            this.title = title;
            this.iconRes = res;
        }
    }
}