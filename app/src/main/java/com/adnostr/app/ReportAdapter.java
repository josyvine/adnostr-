package com.adnostr.app;

import android.content.Context;
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

import java.util.List;

/**
 * ADMIN SUPREMACY: Forensic Feed Multi-Adapter.
 * - Logic: Dynamically builds Category, Tech Field, and Value Pool cards.
 * - State: Identifies 'NEW' items via Orange Glow and Blinking Badge.
 * - Trace: Decodes truncated NPUBs and calculates relative timestamps.
 */
public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.ForensicViewHolder> {

    private final List<JSONObject> eventList;
    private final long lastSeenTimestamp;
    private final OnPurgeListener listener;

    public interface OnPurgeListener {
        void onSurgicalWipe(JSONObject event);
        void onCascadingNuke(String categoryName);
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

    class ForensicViewHolder extends RecyclerView.ViewHolder {
        private final ItemReportCardBinding binding;

        public ForensicViewHolder(ItemReportCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Logic: Maps Nostr metadata to specialized forensic UI components.
         */
        public void bind(JSONObject event) {
            try {
                Context context = itemView.getContext();
                AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

                // 1. Parse Event Metadata
                int kind = event.getInt("kind");
                long createdAt = event.getLong("created_at");
                String pubkey = event.getString("pubkey");
                String contentStr = event.getString("content");
                JSONObject content = new JSONObject(contentStr);

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

                // 3. Map Card Type Logic
                if (kind == 30006) {
                    String subType = content.optString("type", "");
                    if ("category".equals(subType)) {
                        // CATEGORY CARD
                        binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_directions);
                        binding.ivForensicIcon.setColorFilter(Color.parseColor("#4CAF50")); // Green Folder
                        binding.tvReportTitle.setText(content.optString("sub", "Unknown Sub"));
                        binding.tvReportSubtitle.setText("Main: " + content.optString("main", "General"));
                        
                        // Setup Cascading Nuke for Categories
                        binding.btnPurgeItem.setOnClickListener(v -> {
                            if (listener != null) listener.onCascadingNuke(content.optString("sub"));
                        });
                    } else {
                        // TECH FIELD CARD
                        binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_preferences);
                        binding.ivForensicIcon.setColorFilter(Color.parseColor("#2196F3")); // Blue Gear
                        binding.tvReportTitle.setText(content.optString("label", "Unknown Field"));
                        binding.tvReportSubtitle.setText("Target: " + content.optString("category", "General"));
                        
                        // Surgical Wipe
                        binding.btnPurgeItem.setOnClickListener(v -> {
                            if (listener != null) listener.onSurgicalWipe(event);
                        });
                    }
                } else if (kind == 30007) {
                    // VALUE POOL CARD (BRANDS)
                    binding.ivForensicIcon.setImageResource(android.R.drawable.ic_menu_agenda);
                    binding.ivForensicIcon.setColorFilter(Color.parseColor("#FF9800")); // Orange Tag
                    
                    JSONObject specs = content.optJSONObject("specs");
                    String fieldName = (specs != null && specs.keys().hasNext()) ? specs.keys().next() : "Values";
                    String values = (specs != null) ? specs.optString(fieldName, "") : "";
                    
                    binding.tvReportTitle.setText(values);
                    binding.tvReportSubtitle.setText("Seeded for: " + fieldName + " (" + content.optString("category") + ")");
                    
                    // Surgical Wipe
                    binding.btnPurgeItem.setOnClickListener(v -> {
                        if (listener != null) listener.onSurgicalWipe(event);
                    });
                }

                // 4. Trace Metadata (NPUB & Time)
                String truncatedNpub = "npub1..." + pubkey.substring(pubkey.length() - 6);
                binding.tvAuthorTrace.setText(truncatedNpub);

                CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                        createdAt * 1000, 
                        System.currentTimeMillis(), 
                        DateUtils.SECOND_IN_MILLIS);
                binding.tvTimestampTrace.setText(relativeTime);

                // 5. Executioner Gate: Trash icon visible only to Admin
                binding.btnPurgeItem.setVisibility(db.isAdmin() ? View.VISIBLE : View.GONE);

            } catch (Exception e) {
                binding.tvReportTitle.setText("Corrupted Metadata Frame");
                binding.tvReportSubtitle.setText("Parse Error: " + e.getMessage());
            }
        }
    }
}