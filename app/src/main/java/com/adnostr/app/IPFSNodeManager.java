package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import io.ipfs.lite.Config;
import io.ipfs.lite.IPFS;

/**
 * ============================================================
 * AdNostr P2P STORAGE ENGINE
 * Powered by Datahop IPFS-Lite (Modern SDK)
 * ------------------------------------------------------------
 * • No API keys
 * • No registration
 * • No centralized servers
 * • Runs fully embedded inside the app
 * ============================================================
 */
public class IPFSNodeManager {

    private static final String TAG = "AdNostr_IPFSNode";
    private static IPFSNodeManager instance;

    private IPFS ipfs;
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

    // ============================================================
    // START EMBEDDED IPFS NODE
    // ============================================================
    public synchronized void startNode() {
        if (isStarted) return;

        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing embedded IPFS node...");

                // Folder where IPFS stores blocks + keys
                File repoDir = new File(context.getFilesDir(), "ipfs_repo");

                // Default config from Datahop SDK
                Config config = new Config();

                // Create IPFS instance
                ipfs = new IPFS(repoDir, config);

                // Start P2P networking
                ipfs.start();

                isStarted = true;
                Log.i(TAG, "IPFS Node is ONLINE.");

            } catch (Exception e) {
                Log.e(TAG, "CRITICAL: Failed to start IPFS node: " + e.getMessage());
                isStarted = false;
            }
        }).start();
    }

    // ============================================================
    // ADD FILE TO P2P NETWORK (Advertiser uploads image)
    // ============================================================
    public String addFile(File file) throws Exception {
        ensureNodeReady();

        byte[] fileBytes = readFileToBytes(file);

        // Add bytes to IPFS → returns CID
        String cid = ipfs.add(fileBytes);

        Log.i(TAG, "File added to IPFS. CID: " + cid);
        return cid;
    }

    // ============================================================
    // DOWNLOAD FILE FROM P2P NETWORK (User views ad image)
    // ============================================================
    public byte[] getFile(String cid) throws Exception {
        ensureNodeReady();

        Log.d(TAG, "Fetching file from IPFS for CID: " + cid);

        return ipfs.cat(cid);
    }

    // ============================================================
    // STOP NODE (optional graceful shutdown)
    // ============================================================
    public void stopNode() {
        if (ipfs != null) {
            try {
                ipfs.stop();
                isStarted = false;
                Log.i(TAG, "IPFS Node stopped successfully.");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping IPFS node: " + e.getMessage());
            }
        }
    }

    // ============================================================
    // CHECK NODE STATUS
    // ============================================================
    public boolean isNodeReady() {
        return isStarted && ipfs != null;
    }

    // ============================================================
    // INTERNAL SAFETY CHECK
    // ============================================================
    private void ensureNodeReady() throws Exception {
        if (!isStarted || ipfs == null) {
            throw new Exception("IPFS node is not running yet.");
        }
    }

    // ============================================================
    // FILE → BYTE ARRAY HELPER
    // ============================================================
    private byte[] readFileToBytes(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] bytes = new byte[(int) file.length()];
        int read = fis.read(bytes);
        fis.close();

        if (read != bytes.length) {
            throw new IOException("Could not read entire file: " + file.getName());
        }

        return bytes;
    }
}