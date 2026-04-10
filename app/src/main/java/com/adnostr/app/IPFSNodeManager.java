package com.adnostr.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;

/**
 * ============================================================
 * AdNostr P2P STORAGE ENGINE
 * Powered by Datahop IPFS-Lite (Go gomobile binding)
 * 
 * • No API keys
 * • No registration
 * • No centralized servers
 * • Runs fully embedded inside the app
 * ============================================================
 */
public class IPFSNodeManager {

    private static final String TAG = "AdNostr_IPFSNode";
    private static IPFSNodeManager instance;

    private Object peer;
    private final Context context;
    private boolean isStarted = false;
    private String lastError = "";

    /**
     * Private constructor for singleton pattern
     * @param context Application context
     */
    private IPFSNodeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Get singleton instance of IPFSNodeManager
     * @param context Application context
     * @return IPFSNodeManager instance
     */
    public static synchronized IPFSNodeManager getInstance(Context context) {
        if (instance == null) {
            instance = new IPFSNodeManager(context);
        }
        return instance;
    }

    /**
     * ============================================================
     * START EMBEDDED IPFS NODE
     * ============================================================
     * Initializes and starts the embedded IPFS peer node.
     * This runs on a background thread to avoid blocking the UI.
     */
    public synchronized void startNode() {
        if (isStarted) {
            Log.w(TAG, "IPFS node is already running.");
            return;
        }

        new Thread(() -> {
            try {
                Log.i(TAG, "====================================");
                Log.i(TAG, "Initializing embedded IPFS node...");
                Log.i(TAG, "====================================");

                // Create directory where IPFS stores blocks, keys, and configuration
                File repoDir = new File(context.getFilesDir(), "ipfs_repo");
                if (!repoDir.exists()) {
                    boolean created = repoDir.mkdirs();
                    if (!created) {
                        throw new IOException("Failed to create IPFS repository directory");
                    }
                    Log.i(TAG, "Created IPFS repository directory: " + repoDir.getAbsolutePath());
                }

                String repoDirPath = repoDir.getAbsolutePath();
                Log.i(TAG, "IPFS Repository path: " + repoDirPath);

                // Initialize Datahop IPFS peer
                Class<?> mobileClass;
                try {
                    // FIXED: Using the verified class name from the diagnostic report
                    mobileClass = Class.forName("datahop.Datahop");
                } catch (ClassNotFoundException e) {
                    try {
                        mobileClass = Class.forName("io.datahop.Datahop");
                    } catch (ClassNotFoundException e2) {
                        throw new Exception("CRITICAL: Datahop classes completely missing from classpath.");
                    }
                }

                Log.i(TAG, "Datahop class found! Attempting to initialize node...");

                // Attempt to invoke the method. We wrap this in a deep scanner just in case 
                // the GoMobile method name is slightly different (e.g., newNode, Init, startNode).
                Method newNodeMethod;
                try {
                    newNodeMethod = mobileClass.getMethod("NewNode", Context.class, String.class);
                } catch (NoSuchMethodException e) {
                    // SCANNER: If NewNode doesn't exist, dump all available methods so we can see what to call!
                    StringBuilder availableMethods = new StringBuilder("Available methods in Datahop:\n");
                    for (Method m : mobileClass.getMethods()) {
                        availableMethods.append(" - ").append(m.getName()).append("(");
                        for (Class<?> p : m.getParameterTypes()) {
                            availableMethods.append(p.getSimpleName()).append(", ");
                        }
                        availableMethods.append(")\n");
                    }
                    throw new Exception("Method 'NewNode' not found in Datahop library! " + availableMethods.toString());
                }

                peer = newNodeMethod.invoke(null, context, repoDirPath);
                Log.i(TAG, "IPFS Peer object created successfully");

                // Start the peer node
                try {
                    peer.getClass().getMethod("Start").invoke(peer);
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "Warning: 'Start' method not found on peer. It may auto-start.");
                }
                
                Log.i(TAG, "IPFS Peer started successfully");
                isStarted = true;
                lastError = "";

                Log.i(TAG, "====================================");
                Log.i(TAG, "✓ IPFS Node is ONLINE");
                Log.i(TAG, "====================================");

            } catch (Exception e) {
                Log.e(TAG, "CRITICAL: Failed to start IPFS node");
                Log.e(TAG, "Error message: " + e.getMessage());
                Log.e(TAG, "Stack trace: ", e);
                lastError = e.getMessage();
                isStarted = false;
            }
        }).start();
    }

    /**
     * ============================================================
     * ADD FILE TO P2P NETWORK
     * ============================================================
     * Advertiser uploads an image/file to the IPFS network.
     * The file is stored in the peer's local block store and made
     * available to the network.
     * 
     * @param file The file to add to IPFS
     * @return Content Identifier (CID) of the added file
     * @throws Exception if node is not ready or operation fails
     */
    public String addFile(File file) throws Exception {
        ensureNodeReady();

        if (!file.exists()) {
            throw new FileNotFoundException("File does not exist: " + file.getAbsolutePath());
        }

        Log.i(TAG, "====================================");
        Log.i(TAG, "Adding file to IPFS network...");
        Log.i(TAG, "File: " + file.getName());
        Log.i(TAG, "Size: " + file.length() + " bytes");
        Log.i(TAG, "====================================");

        try {
            // Read file into byte array
            byte[] fileBytes = readFileToBytes(file);
            Log.d(TAG, "File read successfully: " + fileBytes.length + " bytes");

            // Add bytes to IPFS network
            // This returns a CID (Content Identifier) that uniquely identifies the file
            String cid = (String) peer.getClass().getMethod("Add", byte[].class)
                    .invoke(peer, (Object) fileBytes);

            Log.i(TAG, "====================================");
            Log.i(TAG, "✓ File added to IPFS successfully");
            Log.i(TAG, "CID: " + cid);
            Log.i(TAG, "====================================");

            return cid;

        } catch (Exception e) {
            Log.e(TAG, "ERROR: Failed to add file to IPFS");
            Log.e(TAG, "Exception: " + e.getMessage());
            throw new Exception("Failed to add file to IPFS: " + e.getMessage(), e);
        }
    }

    /**
     * ============================================================
     * DOWNLOAD FILE FROM P2P NETWORK
     * ============================================================
     * User views an ad image by retrieving it from the IPFS network
     * using its Content Identifier (CID).
     * 
     * @param cid Content Identifier of the file to retrieve
     * @return Byte array containing the file data
     * @throws Exception if node is not ready or file cannot be retrieved
     */
    public byte[] getFile(String cid) throws Exception {
        ensureNodeReady();

        if (cid == null || cid.isEmpty()) {
            throw new IllegalArgumentException("CID cannot be null or empty");
        }

        Log.i(TAG, "====================================");
        Log.i(TAG, "Fetching file from IPFS network...");
        Log.i(TAG, "CID: " + cid);
        Log.i(TAG, "====================================");

        try {
            // Retrieve file data from IPFS using CID
            // The peer will search the network if the block is not in local storage
            byte[] fileData = (byte[]) peer.getClass().getMethod("Cat", String.class)
                    .invoke(peer, cid);

            if (fileData == null || fileData.length == 0) {
                throw new Exception("Retrieved file data is empty for CID: " + cid);
            }

            Log.i(TAG, "====================================");
            Log.i(TAG, "✓ File retrieved from IPFS successfully");
            Log.i(TAG, "Size: " + fileData.length + " bytes");
            Log.i(TAG, "====================================");

            return fileData;

        } catch (Exception e) {
            Log.e(TAG, "ERROR: Failed to retrieve file from IPFS");
            Log.e(TAG, "CID: " + cid);
            Log.e(TAG, "Exception: " + e.getMessage());
            throw new Exception("Failed to retrieve file from IPFS: " + e.getMessage(), e);
        }
    }

    /**
     * ============================================================
     * DELETE FILE FROM P2P NETWORK
     * ============================================================
     * Removes a file from the local IPFS block store.
     * This unpins the content, allowing it to be garbage collected
     * from the local node. The content may still exist on other peers.
     * 
     * @param cid Content Identifier of the file to delete
     * @throws Exception if node is not ready or deletion fails
     */
    public void deleteFile(String cid) throws Exception {
        ensureNodeReady();

        if (cid == null || cid.isEmpty()) {
            throw new IllegalArgumentException("CID cannot be null or empty");
        }

        Log.i(TAG, "====================================");
        Log.i(TAG, "Deleting file from local IPFS storage...");
        Log.i(TAG, "CID: " + cid);
        Log.i(TAG, "====================================");

        try {
            // Check if the peer object has a Remove or Delete method
            // Try Rm method (common in Go IPFS implementations)
            try {
                peer.getClass().getMethod("Rm", String.class).invoke(peer, cid);
                Log.i(TAG, "File unpinned and marked for removal (Rm method)");
            } catch (NoSuchMethodException e1) {
                // Try Remove method as alternative
                try {
                    peer.getClass().getMethod("Remove", String.class).invoke(peer, cid);
                    Log.i(TAG, "File unpinned and marked for removal (Remove method)");
                } catch (NoSuchMethodException e2) {
                    // If neither method exists, log warning but continue
                    // The Datahop library may not expose a direct delete operation
                    Log.w(TAG, "No delete/remove method found in peer. File may remain in local storage.");
                    Log.w(TAG, "Available methods depend on Datahop IPFS-Lite implementation.");
                    return;
                }
            }

            Log.i(TAG, "====================================");
            Log.i(TAG, "✓ File deleted from local storage successfully");
            Log.i(TAG, "====================================");

        } catch (Exception e) {
            Log.e(TAG, "ERROR: Failed to delete file from IPFS");
            Log.e(TAG, "CID: " + cid);
            Log.e(TAG, "Exception: " + e.getMessage());
            throw new Exception("Failed to delete file from IPFS: " + e.getMessage(), e);
        }
    }

    /**
     * ============================================================
     * STOP NODE (Graceful Shutdown)
     * ============================================================
     * Stops the IPFS node, closes connections, and cleans up resources.
     * Call this when the app is about to close or when IPFS is no longer needed.
     */
    public void stopNode() {
        if (peer != null) {
            try {
                Log.i(TAG, "====================================");
                Log.i(TAG, "Stopping IPFS node...");
                Log.i(TAG, "====================================");

                peer.getClass().getMethod("Stop").invoke(peer);

                isStarted = false;
                Log.i(TAG, "====================================");
                Log.i(TAG, "✓ IPFS Node stopped successfully");
                Log.i(TAG, "====================================");

            } catch (Exception e) {
                Log.e(TAG, "ERROR: Exception while stopping IPFS node");
                Log.e(TAG, "Exception: " + e.getMessage());
                isStarted = false;
            }
        } else {
            Log.w(TAG, "IPFS peer is null, nothing to stop");
        }
    }

    /**
     * ============================================================
     * CHECK NODE STATUS
     * ============================================================
     * Determines whether the IPFS node is currently running and ready
     * to handle file operations.
     * 
     * @return true if node is started and peer object exists, false otherwise
     */
    public boolean isNodeReady() {
        boolean ready = isStarted && peer != null;
        Log.d(TAG, "Node ready status: " + ready);
        return ready;
    }

    /**
     * ============================================================
     * INTERNAL SAFETY CHECK
     * ============================================================
     * Verifies that the IPFS node is running before attempting
     * any file operations. Throws an exception if the node is not ready.
     * 
     * @throws Exception if the node is not started or peer is null
     */
    private void ensureNodeReady() throws Exception {
        if (!isStarted || peer == null) {
            String errorMsg = "IPFS node is not running yet. Call startNode() first. Background Error: " + lastError;
            Log.e(TAG, errorMsg);
            throw new Exception(errorMsg);
        }
    }

    /**
     * ============================================================
     * FILE → BYTE ARRAY CONVERSION HELPER
     * ============================================================
     * Reads the entire contents of a file into a byte array.
     * This is used before adding files to IPFS or after retrieving them.
     * 
     * @param file The file to read
     * @return Byte array containing the file contents
     * @throws IOException if the file cannot be read
     */
    private byte[] readFileToBytes(File file) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }

        if (!file.canRead()) {
            throw new IOException("Cannot read file: " + file.getAbsolutePath());
        }

        long fileSize = file.length();
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("File is too large: " + fileSize + " bytes");
        }

        byte[] bytes = new byte[(int) fileSize];

        try (FileInputStream fis = new FileInputStream(file)) {
            int totalBytesRead = 0;
            int bytesRead;

            while (totalBytesRead < bytes.length) {
                bytesRead = fis.read(bytes, totalBytesRead, bytes.length - totalBytesRead);

                if (bytesRead == -1) {
                    break;
                }

                totalBytesRead += bytesRead;
            }

            if (totalBytesRead != bytes.length) {
                throw new IOException(
                    String.format(
                        "Could not read entire file. Expected %d bytes, but read %d bytes from %s",
                        bytes.length,
                        totalBytesRead,
                        file.getName()
                    )
                );
            }

            Log.d(TAG, "Successfully read " + totalBytesRead + " bytes from " + file.getName());
            return bytes;

        } catch (IOException e) {
            Log.e(TAG, "Error reading file to bytes: " + e.getMessage());
            throw e;
        }
    }

    /**
     * ============================================================
     * GET PEER OBJECT (For advanced operations)
     * ============================================================
     * Returns the underlying IPFS peer object for advanced usage
     * if needed by other parts of the application.
     * 
     * @return The peer object, or null if not initialized
     */
    public Object getPeer() {
        return peer;
    }

    /**
     * ============================================================
     * RESET INSTANCE (For testing/cleanup)
     * ============================================================
     * Resets the singleton instance. Use with caution!
     * Typically used in testing or when you need to reinitialize.
     */
    public static synchronized void resetInstance() {
        if (instance != null && instance.isStarted) {
            instance.stopNode();
        }
        instance = null;
    }
}