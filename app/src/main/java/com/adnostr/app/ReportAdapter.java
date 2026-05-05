package com.adnostr.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemReportCardBinding;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADMIN SUPREMACY: Forensic Feed Multi-Adapter.
 * - Logic: Dynamically builds T1, T2, T3 (Tech Specs), and T4 (Value Pool) cards.
 * - State: Identifies 'NEW' items via Orange Glow and Blinking Badge.
 * - Trace: Decodes truncated NPUBs and calculates relative timestamps.
 * 
 * 4-TIER VISUAL MAPPING:
 * - TIER 1: World Icon (Main Category)
 * - TIER 2: Folder Icon (Sub Category)
 * - TIER 3: Gear Icon (Technical Spec Anchor)
 * - TIER 4: List Icon (Value Data Pool)
 * 
 * CROWDSOURCED DATA FIX:
 * - Kind 30007 Parser: Now identifies context (e.g. Bajaj) to prevent "Unknown Field" errors.
 * - Detail Hook: Implemented onClickListener to launch the professional vertical detail viewer.
 * 
 * VOLATILITY FIX:
 * - Sync Feedback: Now identifies if a card was loaded from the Immutable Archive or found Live.
 * 
 * PERFORMANCE UPDATE (ANTI-HANG):
 * - Parsed Object Cache: Implemented a memory-efficient ConcurrentHashMap to prevent redundant 
 *   JSON parsing during scrolling, which was the primary cause of UI thread hangs.
 * 
 * UI STABILIZATION FIX:
 * - Fixed timestamp flickering by rounding currentTime to the second.
 */
public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.ForensicViewHolder> {

    private final List<JSONObject> eventList;
    private final long lastSeenTimestamp;
    private final OnPurgeListener listener;

    // PERFORMANCE CACHE: Prevents the "new JSONObject" loop that hangs the UI thread
    private final Map<String, JSONObject> contentCache = new ConcurrentHashMap<>();

    /**
     * Interface for Admin Governance.
     */
    public interface OnPurgeListener {
        void onSurgicalWipe(JSONObject event);
        void onCascadingNuke(String categoryName);
        void onLocalDismiss(JSONObject event); 
    }

    public ReportAdapter(List<JSONObject> eventList, long lastSeen, OnPurgeListener listener) {
        this.eventList = eventList;
        this.lastSeenTimestamp = lastSeen;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ForensicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemReportCardBinding binding = ItemReportCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ForensicViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ForensicViewHolder holder, int position) {
        holder.bind(eventList.get(position));
    }

    @Override
    public int getItemCount() {
        return eventList != null ? eventList.size() : 0;
    }

    /**
     * Clears the memory cache when the list is refreshed to ensure fresh data.
     */
    public void clearCache() {
        contentCache.clear();
    }

    class ForensicViewHolder extends RecyclerView.ViewHolder {
        private final ItemReportCardBinding binding;

        public ForensicViewHolder(ItemReportCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Logic: Maps Nostr metadata to specialized forensic UI components.
         * UPDATED: Implements 4-Tier Icon Mapping and stabilized timestamps.
         */
        public void bind(JSONObject event) {
            try {
                Context context = itemView.getContext();
                AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

                // 1. Parse Event Metadata
                int kind = event.getInt("kind");
                long createdAt = event.getLong("created_at");
                String pubkey = event.getString("pubkey");
                String eventId = event.optString("id", "");

                // PERFORMANCE OPTIMIZATION: Retrieve from cache or parse once
                JSONObject content;
                if (contentCache.containsKey(eventId)) {
                    content = contentCache.get(eventId);
                } else {
                    String contentStr = event.getString("content");
                    if (!contentStr.trim().startsWith("{")) {
                        binding.tvReportTitle.setText("Raw Frame Trace");
                        binding.tvReportSubtitle.setText(contentStr);
                        return;
                    }
                    content = new JSONObject(contentStr);
                    contentCache.put(eventId, content);
                }

                // 2. Determine NEW Status (Visual State Machine)
                boolean isNew = createdAt > lastSeenTimestamp;
                if (isNew) {
                    binding.cvReportContainer.setStrokeColor(Color.parseColor("#FF6A00")); // Orange Glow
                    binding.cvReportContainer.setStrokeWidth(4);
                    binding.tvNewBadge.setVisibility(View.VISIBLE);
                } else {
                    binding.cvReportContainer.setStrokeColor(Color.parseColor("#333333")); // Flat Grey
                    binding.cvReportContainer.setStrokeWidth(2);
                    binding.tvNewBadge.setVisibility(View.GONE);
                }

                // 3. Map Card Type Logic (4-TIER REPAIR)
                if (kind == 30006) {
                    String subType = content.optString("type", "");
                    
                    if ("category".equals(subType)) {
                        if (content.has("main")) {
                            // TIER 1: MAIN CATEGORY
                            binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_directions); // World/Path
                            binding.ivForensicIcon.setColorFilter(Color.parseColor("#FFD700")); // Gold
                            binding.tvReportTitle.setText(content.optString("main", "Global Sector"));
                            binding.tvReportSubtitle.setText("Hierarchy: Tier 1 (Main)");
                        } else {
                            // TIER 2: SUB CATEGORY
                            binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size); // Folder
                            binding.ivForensicIcon.setColorFilter(Color.parseColor("#4CAF50")); // Green
                            binding.tvReportTitle.setText(content.optString("sub", "Sub Sector"));
                            binding.tvReportSubtitle.setText("Category: " + content.optString("main", "General"));
                        }

                        // Setup Cascading Nuke for Categories (T1 and T2)
                        binding.btnPurgeItem.setOnClickListener(v -> {
                            if (listener != null) listener.onCascadingNuke(content.optString("sub", content.optString("main")));
                        });

                    } else if ("field".equals(subType)) {
                        // TIER 3: TECH FIELD ANCHOR (Brand, Model, Year)
                        binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_preferences); // Gear
                        binding.ivForensicIcon.setColorFilter(Color.parseColor("#2196F3")); // Blue
                        binding.tvReportTitle.setText(content.optString("label", "Unknown Anchor"));
                        binding.tvReportSubtitle.setText("Anchor for: " + content.optString("category", "General"));

                        // Surgical Wipe for Anchors
                        binding.btnPurgeItem.setOnClickListener(v -> {
                            if (listener != null) listener.onSurgicalWipe(event);
                        });
                    }
                } else if (kind == 30007) {
                    // TIER 4: VALUE POOL (Bajaj, Pulsar, 2024)
                    binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_agenda); // List
                    binding.ivForensicIcon.setColorFilter(Color.parseColor("#FF9800")); // Orange

                    JSONObject specs = content.optJSONObject("specs");
                    JSONObject contextObj = content.optJSONObject("context");

                    // Context identification (e.g. Bajaj)
                    String contextValue = (contextObj != null) ? contextObj.optString("value", "") : "";
                    String fieldName = "Values";
                    if (specs != null && specs.length() > 0) {
                        Iterator<String> keys = specs.keys();
                        fieldName = keys.next();
                        fieldName = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                    }

                    String displayTitle = contextValue.isEmpty() ? fieldName : contextValue + " - " + fieldName;
                    binding.tvReportTitle.setText(displayTitle);
                    binding.tvReportSubtitle.setText("Data Pool for Category: " + content.optString("category", "General"));

                    binding.btnPurgeItem.setOnClickListener(v -> {
                        if (listener != null) listener.onSurgicalWipe(event);
                    });
                }

                // 4. Trace Metadata (NPUB & Time)
                String truncatedNpub = "npub1..." + pubkey.substring(pubkey.length() - 6);
                binding.tvAuthorTrace.setText(truncatedNpub);

                // =========================================================================
                // FLICKER FIX: Stabilized relative time logic.
                // =========================================================================
                long eventTimeMillis = createdAt * 1000;
                long currentTimeMillis = (System.currentTimeMillis() / 1000) * 1000; // Round to second to stop label jitter
                
                CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                        eventTimeMillis, 
                        currentTimeMillis, 
                        DateUtils.SECOND_IN_MILLIS);

                // Identify source for forensic transparency
                String syncSource = (createdAt < (System.currentTimeMillis() / 1000) - 86400) ? " [Archived]" : " [Live]";
                binding.tvTimestampTrace.setText(relativeTime + syncSource);

                // 5. Executioner Gate: Trash icon visible only to Admin
                binding.btnPurgeItem.setVisibility(db.isAdmin() ? View.VISIBLE : View.GONE);

                // Detail viewer trigger
                binding.cvReportContainer.setOnClickListener(v -> {
                    Intent intent = new Intent(context, ForensicDetailActivity.class);
                    intent.putExtra("EVENT_JSON", event.toString());
                    context.startActivity(intent);
                });

                // Local Dismiss logic
                binding.cvReportContainer.setOnLongClickListener(v -> {
                    if (listener != null) {
                        listener.onLocalDismiss(event);
                    }
                    return true;
                });

            } catch (Exception e) {
                binding.tvReportTitle.setText("Corrupted Metadata Frame");
                binding.tvReportSubtitle.setText("Parse Error: " + e.getMessage());
            }
        }
    }
}