package com.adnostr.app;

import android.content.Context;
import android.util.Log;
import java.io.File;
import org.json.JSONObject;

/**
 * Decentralized Storage Utility for AdNostr.
 * UPDATED: Replaced centralized HTTP gateways with local P2P IPFS Node logic.
 * This class now acts as a bridge to the embedded Go-IPFS Lite node.
 * 
 * NO REGISTRATION REQUIRED. NO API KEYS. TRUE PEER-TO-PEER.
 */
public class IPFSHelper {

    private static final String TAG = "AdNostr_IPFSHelper";
    
    // Public Read-Only Gateways (Used for the Automatic Fallback Safety Net)
    // These gateways do not require registration for viewing/downloading images.
    private static final String FALLBACK_GATEWAY_1 = "https://cloudflare-ipfs.com/ipfs/";
    private static final String FALLBACK_GATEWAY_2 = "https://ipfs.io/ipfs/";
    private static final String FALLBACK_GATEWAY_3 = "https://gateway.pinata.cloud/ipfs/";

    /**
     * Interface for handling asynchronous IPFS results.
     */
    public interface IPFSUploadCallback {
        void onSuccess(String cid, String gatewayUrl);
        void onFailure(Exception e);
    }

    /**
     * ADVERTISER LOGIC: Adds an image to the local P2P node.
     * This does NOT upload to a server; it hashes the file locally and 
     * begins announcing it to the P2P swarm via bootstrap peers.
     * 
     * @param imageFile The local image file to be decentralized.
     * @param callback The result listener.
     */
    public static void uploadImage(final File imageFile, final IPFSUploadCallback callback) {
        // We run this in a thread to keep the UI smooth
        new Thread(() -> {
            try {
                Log.i(TAG, "Starting local P2P 'Add' for: " + imageFile.getName());

                // 1. Get the instance of the internal P2P node
                // Note: IPFSNodeManager must be initialized in AdNostrApplication first
                IPFSNodeManager nodeManager = IPFSNodeManager.getInstance(null);

                if (!nodeManager.isNodeReady()) {
                    throw new Exception("P2P Node is still starting. Please wait a few seconds.");
                }

                // 2. Add the file to the local P2P repository
                // This returns the mathematical CID (Content Identifier)
                String cid = nodeManager.addFile(imageFile);

                // 3. Generate the primary decentralized link
                String ipfsLink = "ipfs://" + cid;

                Log.i(TAG, "P2P Add Success. Local CID: " + cid);

                // 4. Return success to the Fragment
                if (callback != null) {
                    callback.onSuccess(cid, ipfsLink);
                }

            } catch (Exception e) {
                Log.e(TAG, "P2P Storage Failed: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure(e);
                }
            }
        }).start();
    }

    /**
     * USER LOGIC: Automatic Fallback Resolver.
     * If the P2P network is slow (due to NAT/Firewall), this method provides 
     * the HTTP mirror URL to ensure the ad shows up instantly.
     * 
     * @param cid The Content Identifier from the Nostr JSON.
     * @return A public HTTP URL that mirrors the decentralized content.
     */
    public static String getFallbackUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        
        // Remove the ipfs:// prefix if present
        String cleanCid = cid.replace("ipfs://", "");
        
        // We use Cloudflare as the primary fallback because it is the fastest 
        // HTTP bridge to the IPFS P2P network.
        return FALLBACK_GATEWAY_1 + cleanCid;
    }

    /**
     * Converts an IPFS CID into a standard ipfs:// protocol string.
     * 
     * @param cid The Content Identifier.
     * @return Protocol formatted string.
     */
    public static String generateGatewayUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        String cleanCid = cid.replace("ipfs://", "");
        return "ipfs://" + cleanCid;
    }
}