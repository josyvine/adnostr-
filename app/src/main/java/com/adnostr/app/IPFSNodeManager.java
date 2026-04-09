package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// These imports refer to the Go-mobile bindings included in your build.gradle
import ipfslite.IpfsLite;
import ipfslite.Peer;

/**
 * The Heart of AdNostr's P2P Storage.
 * Manages an embedded IPFS Lite node on the device.
 * NO REGISTRATION, NO API KEYS, NO CENTRAL SERVER.
 */
public class IPFSNodeManager {

    private static final String TAG = "AdNostr_IPFSNode";
    private static IPFSNodeManager instance;
    
    private IpfsLite node;
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
     * Initializes and starts the embedded IPFS node.
     * This should be called from the AdNostrApplication or a Foreground Service.
     */
    public synchronized void startNode() {
        if (isStarted) return;

        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing Decentralized IPFS Node...");

                // 1. Setup the local data directory
                File repoPath = new File(context.getFilesDir(), "ipfs_repo");
                if (!repoPath.exists()) repoPath.mkdirs();

                // 2. Configure the Node with the Bootstrap Peers
                node = IpfsLite.newNode(repoPath.getAbsolutePath());

                // 3. Connect to the swarm
                for (String peerAddr : BOOTSTRAP_PEERS) {
                    try {
                        node.connect(peerAddr);
                        Log.d(TAG, "Connected to IPFS Bootstrap Peer: " + peerAddr);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to connect to peer: " + peerAddr);
                    }
                }

                isStarted = true;
                Log.i(TAG, "IPFS Node is ONLINE and P2P Swarm is active.");

            } catch (Exception e) {
                Log.e(TAG, "CRITICAL: IPFS Node failed to start: " + e.getMessage());
            }
        }).start();
    }

    /**
     * ADVERTISER ACTION: Adds a file to the local IPFS node.
     * Returns the CID string (e.g. Qm... or bafy...).
     */
    public String addFile(File file) throws Exception {
        if (node == null || !isStarted) {
            throw new Exception("IPFS Node is not started yet.");
        }

        byte[] fileData = readFileToByteArray(file);
        
        // node.add() performs the hashing and adds it to the local P2P blockstore.
        // It returns the CID which is the content fingerprint.
        String cid = node.add(fileData);
        
        // We 'pin' it locally so the Advertiser's phone will always host it 
        // until they choose to delete the ad.
        node.pin(cid);
        
        Log.i(TAG, "File Added to P2P Swarm. CID: " + cid);
        return cid;
    }

    /**
     * USER ACTION: Fetches a file from the P2P network.
     */
    public byte[] getFile(String cid) throws Exception {
        if (node == null || !isStarted) {
            throw new Exception("IPFS Node is offline.");
        }

        Log.d(TAG, "Requesting CID from P2P Network: " + cid);
        
        // node.cat() fetches the blocks from the swarm (Advertiser -> Bootstrap -> User)
        return node.cat(cid);
    }

    /**
     * DELETE ACTION: Removes the image from the local phone storage.
     * Use this when the Advertiser deletes an ad to stop storage bloat.
     */
    public void deleteFile(String cid) {
        if (node == null || !isStarted) return;

        try {
            // Unpin tells the node this CID is no longer a priority to host.
            node.unpin(cid);
            
            // Trigger a Garbage Collection to physically wipe the blocks from disk.
            node.repoGC();
            
            Log.i(TAG, "CID Unpinned and Deleted from local storage: " + cid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete CID: " + e.getMessage());
        }
    }

    public boolean isNodeReady() {
        return isStarted;
    }

    public void stopNode() {
        if (node != null) {
            node.close();
            isStarted = false;
            Log.i(TAG, "IPFS Node Shutdown Complete.");
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