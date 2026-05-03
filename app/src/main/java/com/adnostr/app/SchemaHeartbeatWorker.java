package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * NEW: THE CROWDSOURCED DATA HEARTBEAT
 * Role: Autonomous Background Re-Publisher.
 * Logic: Wakes up every hour to refresh the ephemeral Nostr relays with the 
 * Permanent Source of Truth (Forensic Archive).
 * 
 * This worker solves the "Decentralized Volatility" problem by ensuring that 
 * crowdsourced categories and technical specifications are never permanently 
 * pruned from the network indexes.
 */
public class SchemaHeartbeatWorker extends Worker {

    private static final String TAG = "AdNostr_Heartbeat";
    private final Context context;

    public SchemaHeartbeatWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Heartbeat Wake-up: Checking relay data persistence...");

        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);

            // 1. MASTER SECURITY GATE
            // Only the Admin identity is authorized to anchor the global marketplace schema.
            // This prevents standard users from accidentally flooding relays with duplicates.
            if (!db.isAdmin()) {
                Log.w(TAG, "Heartbeat Aborted: Current device is not the authorized Administrative Anchor.");
                return Result.success();
            }

            // 2. INTEGRITY CHECK
            // Check if we actually have data to protect in our Immutable Forensic Archive.
            String archiveData = db.getForensicArchive();
            if (archiveData == null || archiveData.equals("[]") || archiveData.length() < 20) {
                Log.d(TAG, "Heartbeat Standby: Immutable Archive is empty. No metadata to re-publish.");
                return Result.success();
            }

            Log.i(TAG, "Network Healing Initiated: Pushing tiered metadata hierarchy to relays.");

            // 3. EXECUTE SEQUENTIAL HEALING
            // We hand over the task to the MarketplaceSchemaManager which handles:
            // - Hierarchical sorting (Cat -> Sub -> Spec -> Brand)
            // - Fresh BIP-340 Timestamp signing
            // - Inter-tier timing delays to prevent relay flood rejection.
            MarketplaceSchemaManager.executeSequentialHealing(context);

            // 4. LOG SUCCESS
            // This confirms that the "Collective Memory" has been refreshed.
            Log.i(TAG, "Heartbeat Successful: Global marketplace database refreshed and pruning clocks reset.");
            
            return Result.success();

        } catch (Exception e) {
            // If something fails (e.g., identity error or JSON corruption), 
            // the work is marked for retry by the Android System.
            Log.e(TAG, "Heartbeat Critical Failure: " + e.getMessage());
            return Result.retry();
        }
    }
}