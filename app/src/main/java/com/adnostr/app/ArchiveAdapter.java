package com.adnostr.app;

import android.content.Context;
import android.graphics.Color;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.adnostr.app.databinding.ItemArchiveCardBinding;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE TRUTH ANCHOR ADAPTER: Read-Only Memory Feed for Advertiser B.
 * - Logic: Dynamically builds T1, T2, T3 (Tech Specs), and T4 (Value Pool) cards.
 * - Branding: Strictly uses ic_nav_report for all hierarchy tiers.
 * - Security: Read-Only mode. No purge or deletion logic implemented.
 * - Stability: Uses pre-parsed JSON cache to prevent UI thread stalls.
 */
public class ArchiveAdapter extends RecyclerView.Adapter<ArchiveAdapter.ArchiveViewHolder> {

    private final List<JSONObject> eventList;

    // PERFORMANCE CACHE: Prevents the "new JSONObject" loop that hangs the UI thread
    private final Map<String, JSONObject> contentCache = new ConcurrentHashMap<>();

    public ArchiveAdapter(List<JSONObject> eventList) {
        this.eventList = eventList;
    }

    @NonNull
    @Override
    public ArchiveViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemArchiveCardBinding binding = ItemArchiveCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ArchiveViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ArchiveViewHolder holder, int position) {
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

    class ArchiveViewHolder extends RecyclerView.ViewHolder {
        private final ItemArchiveCardBinding binding;

        public ArchiveViewHolder(ItemArchiveCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Logic: Maps Nostr metadata to specialized read-only UI components.
         * FIXED: Robust parsing for contextual value pools (Bajaj models/years).
         * OPTIMIZED: Uses ic_nav_report branding with tier-based color coding.
         */
        public void bind(JSONObject event) {
            try {
                Context context = itemView.getContext();

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
                        binding.tvArchiveTitle.setText("Raw Frame Trace");
                        binding.tvArchiveSubtitle.setText(contentStr);
                        return;
                    }
                    content = new JSONObject(contentStr);
                    contentCache.put(eventId, content);
                }

                // 2. ALWAYS USE ic_nav_report for Branding
                binding.ivArchiveIcon.setImageResource(R.drawable.ic_nav_report);

                // 3. Map Card Hierarchy Logic (4-TIER REPAIR)
                if (kind == 30006) {
                    String subType = content.optString("type", "");
                    
                    if ("category".equals(subType)) {
                        if (content.has("main")) {
                            // TIER 1: MAIN CATEGORY
                            binding.ivArchiveIcon.setColorFilter(Color.parseColor("#FFD700")); // Gold Anchor
                            binding.tvArchiveTitle.setText(content.optString("main", "Global Sector"));
                            binding.tvArchiveSubtitle.setText("Truth Level: Tier 1 (Main)");
                        } else {
                            // TIER 2: SUB CATEGORY
                            binding.ivArchiveIcon.setColorFilter(Color.parseColor("#4CAF50")); // Green Anchor
                            binding.tvArchiveTitle.setText(content.optString("sub", "Sub Sector"));
                            binding.tvArchiveSubtitle.setText("Anchor Category: " + content.optString("main", "General"));
                        }
                    } else if ("field".equals(subType)) {
                        // TIER 3: TECH FIELD ANCHOR (Brand, Model, Year)
                        binding.ivArchiveIcon.setColorFilter(Color.parseColor("#2196F3")); // Blue Anchor
                        binding.tvArchiveTitle.setText(content.optString("label", "Unknown Anchor"));
                        binding.tvArchiveSubtitle.setText("Spec Anchor for: " + content.optString("category", "General"));
                    }
                } else if (kind == 30007) {
                    // TIER 4: VALUE POOL (Bajaj, Pulsar, 2024)
                    binding.ivArchiveIcon.setColorFilter(Color.parseColor("#FF9800")); // Orange Anchor

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
                    binding.tvArchiveTitle.setText(displayTitle);
                    binding.tvArchiveSubtitle.setText("Data Pool for category: " + content.optString("category", "General"));
                }

                // 4. Trace Metadata (NPUB & Time)
                String truncatedNpub = "npub1..." + pubkey.substring(pubkey.length() - 6);
                binding.tvAuthorTrace.setText("Origin: " + truncatedNpub);

                // =========================================================================
                // STABILIZED RELATIVE TIME: Round to second to stop label flicker
                // =========================================================================
                long eventTimeMillis = createdAt * 1000;
                long currentTimeMillis = (System.currentTimeMillis() / 1000) * 1000; 
                
                CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                        eventTimeMillis, 
                        currentTimeMillis, 
                        DateUtils.SECOND_IN_MILLIS);

                binding.tvTimestampTrace.setText(relativeTime + " [Hard-Locked]");

                // 5. Executioner Gate: Read-Only Archive. No delete buttons here.

            } catch (Exception e) {
                binding.tvArchiveTitle.setText("Corrupted Memory Frame");
                binding.tvArchiveSubtitle.setText("Parse Error: " + e.getMessage());
            }
        }
    }
}