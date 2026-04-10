package com.adnostr.app;

import android.content.Context;
import android.util.Log;
import java.io.File;
import org.json.JSONObject;

/**
 * Decentralized Storage Utility for AdNostr.
 * Acts as the bridge between the UI and the Datahop P2P Engine.
 * 
 * NO REGISTRATION REQUIRED. NO API KEYS. 
 * ZERO DEPENDENCY ON CENTRALIZED UPLOAD PROVIDERS.
 */
public class IPFSHelper {

    private static final String TAG = "AdNostr_IPFSHelper";

    // Public Read-Only Gateways (Used for the Automatic Fallback Safety Net)
    // Uploading to these is restricted, but viewing/downloading is free and anonymous.
    private static final String FALLBACK_GATEWAY_1 = "https://cloudflare-ipfs.com/ipfs/";
    private static final String FALLBACK_GATEWAY_2 = "https://ipfs.io/ipfs/";

    /**
     * Interface for handling asynchronous IPFS results.
     */
    public interface IPFSUploadCallback {
        void onSuccess(String cid, String gatewayUrl);
        void onFailure(Exception e);
    }

    /**
     * ADVERTISER LOGIC: Adds an image to the local P2P node repository.
     * The file stays on the phone and is announced to the network. 
     * No data is sent to a central server.
     * 
     * FIXED: Implemented a retry-loop to wait for the P2P engine to warm up 
     * instead of throwing an exception immediately.
     * 
     * @param imageFile The local image file selected by the advertiser.
     * @param callback The result listener.
     */
    public static void uploadImage(final File imageFile, final IPFSUploadCallback callback) {
        new Thread(() -> {
            try {
                Log.i(TAG, "Initiating local P2P hosting for: " + imageFile.getName());

                // 1. Get instance of the Datahop Node Manager
                IPFSNodeManager nodeManager = IPFSNodeManager.getInstance(null);

                // --- START FIX: RETRY LOGIC ---
                int maxRetries = 10; // Wait up to 10 seconds total
                while (!nodeManager.isNodeReady() && maxRetries > 0) {
                    Log.d(TAG, "P2P Engine warming up... waiting 1s. Retries left: " + maxRetries);
                    try {
                        Thread.sleep(1000); // Wait 1 second before checking again
                    } catch (InterruptedException ignored) {
                    }
                    maxRetries--;
                }
                // --- END FIX ---

                if (!nodeManager.isNodeReady()) {
                    throw new Exception("P2P Engine is still warming up. Please try again in 5 seconds.");
                }

                // 2. Add the file to the local P2P blockstore
                // This generates the unique mathematical CID (Content Identifier)
                String cid = nodeManager.addFile(imageFile);

                // 3. Construct the protocol link for Nostr JSON
                String ipfsProtocolLink = "ipfs://" + cid;

                Log.i(TAG, "P2P Hosting Success. CID: " + cid);

                // 4. Return success back to the CreateAdFragment
                if (callback != null) {
                    callback.onSuccess(cid, ipfsProtocolLink);
                }

            } catch (Exception e) {
                Log.e(TAG, "P2P Hosting Failed: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure(e);
                }
            }
        }).start();
    }

    /**
     * USER LOGIC: Automatic Fallback Resolver.
     * If a direct P2P 'leech' from the Advertiser's phone is blocked by 
     * a mobile firewall/NAT, this method provides a public HTTP bridge URL.
     * 
     * @param cid The IPFS CID (with or without protocol prefix).
     * @return A fast public HTTP mirror link.
     */
    public static String getFallbackUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";

        // Remove the ipfs:// prefix to get the raw CID string
        String cleanCid = cid.replace("ipfs://", "");

        // Return the fastest public mirror (Cloudflare)
        return FALLBACK_GATEWAY_1 + cleanCid;
    }

    /**
     * Standardizes a CID into an ipfs:// formatted string for storage in Nostr events.
     */
    public static String generateGatewayUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        String cleanCid = cid.replace("ipfs://", "");
        return "ipfs://" + cleanCid;
    }
}