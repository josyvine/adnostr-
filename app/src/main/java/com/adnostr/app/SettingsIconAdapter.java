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
 * 
 * UPGRADE: Integrated Professional Material Icons for all Command functions.
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

    private final List<SettingItem> items = new ArrayList<>();
    private final OnSettingClickListener listener;

    /**
     * Interface to communicate grid clicks back to the SettingsFragment.
     */
    public interface OnSettingClickListener {
        void onSettingClicked(int commandType);
    }

    /**
     * Constructor filters the icon list based on the User's Role.
     * UPDATED: Points to new professional R.drawable assets instead of system drawables.
     */
    public SettingsIconAdapter(String userRole, OnSettingClickListener listener) {
        this.listener = listener;

        // 1. Profile / Username Icon (Available for Users only)
        if (RoleSelectionActivity.ROLE_USER.equals(userRole)) {
            items.add(new SettingItem(CMD_PROFILE, "Profile", R.drawable.ic_cmd_profile));
        }

        // 2. Mode Switch Icon (Available to Everyone)
        items.add(new SettingItem(CMD_MODE_SWITCH, "Switch Mode", R.drawable.ic_cmd_switch));

        // 3. CLOUDFLARE STORAGE (Strict: Advertiser Only)
        if (RoleSelectionActivity.ROLE_ADVERTISER.equals(userRole)) {
            items.add(new SettingItem(CMD_CLOUDFLARE, "Storage", R.drawable.ic_cmd_storage));

            // 4. MY HASHTAGS REGISTRY (Strict: Advertiser Only)
            // Added as part of the Hybrid Hashtag Registry enhancement
            items.add(new SettingItem(CMD_MY_HASHTAGS, "Registry", R.drawable.ic_cmd_registry));
        }

        // 5. History Shortcut (Everyone)
        items.add(new SettingItem(CMD_HISTORY, "History", R.drawable.ic_cmd_history));

        // 6. NEW: Identity Backup (Available to Everyone)
        // Uses the professional cloud-save icon
        items.add(new SettingItem(CMD_BACKUP, "Backup", R.drawable.ic_cmd_backup));

        // 7. System Reset (Everyone)
        items.add(new SettingItem(CMD_RESET, "Reset App", R.drawable.ic_cmd_reset));
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
        SettingItem item = items.get(position);
        holder.bind(item, listener);
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