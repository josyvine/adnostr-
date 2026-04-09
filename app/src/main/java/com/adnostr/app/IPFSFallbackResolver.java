package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intelligent Decentralized Content Resolver.
 * Implements a "Race" between the P2P Network and HTTP Gateways.
 * 
 * Ensures that Ad images load instantly even if the Advertiser is behind 
 * a strict Mobile NAT or is temporarily offline.
 */
public class IPFSFallbackResolver {

    private static final String TAG = "AdNostr_IPFSResolver";
    
    // Time to wait for P2P network before switching to HTTP Gateway
    private static final int P2P_TIMEOUT_MS = 2500;

    /**
     * Interface to return the resolved image source.
     */
    public interface ResolveCallback {
        // Returns raw bytes from P2P or an HTTP String URL as fallback
        void onSourceReady(Object source); 
        void onFailure(Exception e);
    }

    /**
     * Attempts to find the image on the P2P swarm.
     * Automatically falls back to HTTP mirror if P2P takes too long.
     * 
     * @param context Used to get the Node instance.
     * @param cid The IPFS Content Identifier (without protocol prefix).
     * @param callback Result listener.
     */
    public static void resolveImage(Context context, final String cid, final ResolveCallback callback) {
        if (cid == null || cid.isEmpty()) {
            callback.onFailure(new Exception("Invalid CID"));
            return;
        }

        final String cleanCid = cid.replace("ipfs://", "");
        final AtomicBoolean isResolved = new AtomicBoolean(false);
        final Handler timeoutHandler = new Handler(Looper.getMainLooper());

        // 1. START THE FALLBACK TIMER
        // If P2P fetch is too slow, we switch to the HTTP bridge.
        final Runnable fallbackTask = () -> {
            if (isResolved.compareAndSet(false, true)) {
                Log.w(TAG, "P2P fetch slow for " + cleanCid + ". Switching to HTTP Fallback.");
                
                // Get the mirror URL from our Helper class
                String fallbackUrl = IPFSHelper.getFallbackUrl(cleanCid);
                callback.onSourceReady(fallbackUrl);
            }
        };
        timeoutHandler.postDelayed(fallbackTask, P2P_TIMEOUT_MS);

        // 2. START THE P2P FETCH
        new Thread(() -> {
            try {
                IPFSNodeManager nodeManager = IPFSNodeManager.getInstance(context);

                if (nodeManager.isNodeReady()) {
                    Log.d(TAG, "Attempting P2P fetch for CID: " + cleanCid);
                    
                    // nodeManager.getFile calls node.cat() which searches the swarm
                    byte[] imageData = nodeManager.getFile(cleanCid);

                    if (imageData != null && imageData.length > 0) {
                        // Success! P2P data found.
                        if (isResolved.compareAndSet(false, true)) {
                            // Cancel the fallback timer
                            timeoutHandler.removeCallbacks(fallbackTask);
                            
                            Log.i(TAG, "P2P Fetch SUCCESS for CID: " + cleanCid);
                            
                            // Return the raw byte array for the image loader (Coil)
                            new Handler(Looper.getMainLooper()).post(() -> 
                                callback.onSourceReady(imageData)
                            );
                        }
                    }
                } else {
                    Log.w(TAG, "IPFS Node not ready for P2P fetch. Waiting for fallback.");
                }

            } catch (Exception e) {
                Log.e(TAG, "P2P Fetch Interrupted for " + cleanCid + ": " + e.getMessage());
                // No need to trigger failure here; the fallback timer will handle it.
            }
        }).start();
    }
}