package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

// Datahop IPFS-Lite imports provided by the AAR library
import io.datahop.ipfslite.IPFS;

/**
 * The Heart of AdNostr's P2P Storage.
 * Powered by Datahop IPFS-Lite for modern Android P2P networking.
 * NO REGISTRATION, NO API KEYS, NO CENTRAL SERVER.
 */
public class IPFSNodeManager {

    private static final String TAG = "AdNostr_IPFSNode";
    private static IPFSNodeManager instance;
    
    private IPFS ipfs;
    private final Context context;
    private boolean isStarted = false;

    // YOUR PUBLIC BOOTSTRAP PEERS
    private final String[] BOOTSTRAP_PEERS = {
            "/ip4/104.131.131.82/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8ZdeZW6u5P9H3R3b29",
            "/ip4/104.236.179.241/tcp/4001/ipfs/QmSoLueR4xBeUbY9WZ9xGUUxunbKWcrNFTDAadQJmocnWm",
            "/ip4/128.199.219.111/tcp/4001/ipfs/QmSoLer265NRgSp2LA3dPaeykiS1J6DifTC88f5uVQKNAd",
            "/ip4/104.236.76.40/tcp/4001/ipfs/QmSoLer265NRgSp2LA3dPaeykiS1J6DifTC88f5uVQKNAd"
    };

    private IPFSNodeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized IPFSNodeManager getInstance(Context context) {
        if (instance == null) {
            instance = new IPFSNodeManager(context);
        }
        return instance;
    }

    /**
     * Initializes and starts the embedded Datahop IPFS node.
     */
    public synchronized void startNode() {
        if (isStarted) return;

        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing Datahop P2P Node...");

                // 1. Get the Datahop IPFS instance
                ipfs = IPFS.getInstance(context);

                // 2. Start the node
                // This initializes the local repo and starts the P2P networking stack
                ipfs.start();

                // 3. Connect to the public swarm via your bootstrap peers
                for (String peerAddr : BOOTSTRAP_PEERS) {
                    try {
                        ipfs.connect(peerAddr);
                        Log.d(TAG, "Connected to P2P Bootstrap Peer: " + peerAddr);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not reach peer: " + peerAddr);
                    }
                }

                isStarted = true;
                Log.i(TAG, "P2P Node is ONLINE. Sharing is enabled.");

            } catch (Exception e) {
                Log.e(TAG, "CRITICAL: P2P Node failed to start: " + e.getMessage());
                isStarted = false;
            }
        }).start();
    }

    /**
     * ADVERTISER ACTION: Adds a file to the local P2P repository.
     * 
     * @param file The image file to be hosted.
     * @return The resulting IPFS CID.
     */
    public String addFile(File file) throws Exception {
        if (ipfs == null || !isStarted) {
            throw new Exception("P2P Engine is offline.");
        }

        byte[] fileData = readFileToByteArray(file);
        
        // Datahop adds the bytes and returns the mathematical CID
        String cid = ipfs.add(fileData);
        
        Log.i(TAG, "Image hosted on P2P node. CID: " + cid);
        return cid;
    }

    /**
     * USER ACTION: Fetches an image from the P2P network.
     * 
     * @param cid The CID of the image.
     * @return Raw byte array of the image.
     */
    public byte[] getFile(String cid) throws Exception {
        if (ipfs == null || !isStarted) {
            throw new Exception("P2P Node is not ready.");
        }

        Log.d(TAG, "Requesting data from P2P swarm for CID: " + cid);
        
        // Fetches blocks from the advertiser's phone or other seeding peers
        return ipfs.get(cid);
    }

    /**
     * DELETE ACTION: Physically removes the file from the local device.
     */
    public void deleteFile(String cid) {
        if (ipfs == null || !isStarted) return;

        try {
            // Remove the content from the local blockstore
            ipfs.remove(cid);
            Log.i(TAG, "P2P Storage Cleared for CID: " + cid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to wipe P2P content: " + e.getMessage());
        }
    }

    public boolean isNodeReady() {
        return isStarted && ipfs != null;
    }

    public void stopNode() {
        if (ipfs != null) {
            try {
                ipfs.stop();
                isStarted = false;
                Log.i(TAG, "P2P Node Shutdown Successful.");
            } catch (Exception e) {
                Log.e(TAG, "Error during P2P shutdown: " + e.getMessage());
            }
        }
    }

    private byte[] readFileToByteArray(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] bArray = new byte[(int) file.length()];
        fis.read(bArray);
        fis.close();
        return bArray;
    }
}