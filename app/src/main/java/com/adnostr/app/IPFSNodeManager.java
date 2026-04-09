package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import io.datahop.Node;

/**
 * The Heart of AdNostr's P2P Storage.
 * Powered by Datahop IPFS-Lite for modern Android P2P networking.
 * NO REGISTRATION, NO API KEYS, NO CENTRAL SERVER.
 */
public class IPFSNodeManager {

    private static final String TAG = "AdNostr_IPFSNode";
    private static IPFSNodeManager instance;

    private Node node;
    private final Context context;
    private boolean isStarted = false;

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

                // Create repo folder inside app storage
                File repoPath = new File(context.getFilesDir(), "datahop_repo");

                // Create Datahop Node
                node = new Node(repoPath.getAbsolutePath());

                // Start P2P engine
                node.start();

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
     */
    public String addFile(File file) throws Exception {
        if (node == null || !isStarted) {
            throw new Exception("P2P Engine is offline.");
        }

        byte[] fileData = readFileToByteArray(file);

        // Add bytes to IPFS and get CID
        String cid = node.addBytes(fileData);

        Log.i(TAG, "Image hosted on P2P node. CID: " + cid);
        return cid;
    }

    /**
     * USER ACTION: Fetches an image from the P2P network.
     */
    public byte[] getFile(String cid) throws Exception {
        if (node == null || !isStarted) {
            throw new Exception("P2P Node is not ready.");
        }

        Log.d(TAG, "Requesting data from P2P swarm for CID: " + cid);

        return node.getBytes(cid);
    }

    /**
     * DELETE ACTION: Removes the file from local device blockstore.
     */
    public void deleteFile(String cid) {
        if (node == null || !isStarted) return;

        try {
            node.remove(cid);
            Log.i(TAG, "P2P Storage Cleared for CID: " + cid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to wipe P2P content: " + e.getMessage());
        }
    }

    /**
     * Checks if the P2P stack is running.
     */
    public boolean isNodeReady() {
        return isStarted && node != null;
    }

    /**
     * Gracefully shuts down the P2P engine.
     */
    public void stopNode() {
        if (node != null) {
            try {
                node.stop();
                isStarted = false;
                Log.i(TAG, "P2P Node Shutdown Successful.");
            } catch (Exception e) {
                Log.e(TAG, "Error during P2P shutdown: " + e.getMessage());
            }
        }
    }

    /**
     * Internal helper to read file bytes.
     */
    private byte[] readFileToByteArray(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] bArray = new byte[(int) file.length()];
        int bytesRead = fis.read(bArray);
        fis.close();

        if (bytesRead != bArray.length) {
            throw new IOException("Could not read full file: " + file.getName());
        }

        return bArray;
    }
}