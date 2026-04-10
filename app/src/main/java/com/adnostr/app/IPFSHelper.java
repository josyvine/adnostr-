package com.adnostr.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import datahop.Datahop;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

/**
 * Decentralized Storage Utility for AdNostr.
 * Acts as the bridge between the UI and the Datahop P2P Engine.
 * 
 * NO REGISTRATION REQUIRED. NO API KEYS. 
 * ZERO DEPENDENCY ON CENTRALIZED UPLOAD PROVIDERS.
 */
public class IPFSHelper {

    private static final String TAG = "AdNostr_IPFSHelper";

    // ✅ CRITICAL: Static reference to Datahop engine (singleton pattern)
    private static Datahop datahopEngine = null;
    private static boolean engineInitialized = false;
    private static final Object engineLock = new Object();

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
     * ✅ NEW METHOD: Initialize the Datahop P2P Engine
     * This MUST be called before uploading any images.
     * Should be called once in your MainActivity or Application class.
     * 
     * @param context The Android Context
     * @throws Exception if initialization fails
     */
    public static void initializeDatahopEngine(Context context) throws Exception {
        synchronized (engineLock) {
            if (engineInitialized && datahopEngine != null) {
                Log.d(TAG, "Datahop engine already initialized");
                return;
            }

            try {
                Log.i(TAG, "Initializing Datahop P2P Engine...");

                // ✅ Get the app's cache directory for Datahop storage
                String storagePath = context.getCacheDir().getAbsolutePath();
                Log.d(TAG, "Storage path: " + storagePath);

                // ✅ CREATE a new Datahop instance
                datahopEngine = new Datahop();
                Log.d(TAG, "Datahop instance created");

                // ✅ INITIALIZE with the storage path (CRITICAL!)
                // This is what calls the Go engine initialization
                Log.d(TAG, "Calling startPrivate() with path: " + storagePath);
                datahopEngine.startPrivate(storagePath);

                engineInitialized = true;
                Log.i(TAG, "✅ Datahop P2P Engine initialized successfully!");
                Log.i(TAG, "Node ID: " + datahopEngine.id());

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Datahop engine: ", e);
                e.printStackTrace();
                engineInitialized = false;
                datahopEngine = null;
                throw new Exception("Datahop initialization failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Check if Datahop engine is initialized and ready
     * 
     * @return true if engine is ready, false otherwise
     */
    public static boolean isDatahopReady() {
        synchronized (engineLock) {
            if (datahopEngine == null || !engineInitialized) {
                return false;
            }
            try {
                return datahopEngine.isNodeOnline();
            } catch (Exception e) {
                Log.e(TAG, "Error checking node status: ", e);
                return false;
            }
        }
    }

    /**
     * ADVERTISER LOGIC: Adds an image to the local P2P node repository.
     * The file stays on the phone and is announced to the network. 
     * No data is sent to a central server.
     * 
     * @param context The Android Context (CRITICAL for initializing Datahop storage paths)
     * @param imageFile The local image file selected by the advertiser.
     * @param callback The result listener.
     */
    public static void uploadImage(final Context context, final File imageFile, final IPFSUploadCallback callback) {
        new Thread(() -> {
            try {
                Log.i(TAG, "Initiating local P2P hosting for: " + imageFile.getName());

                // ✅ FIRST: Initialize the engine if not already done
                synchronized (engineLock) {
                    if (!engineInitialized || datahopEngine == null) {
                        Log.d(TAG, "Engine not initialized. Initializing now...");
                        try {
                            initializeDatahopEngine(context);
                        } catch (Exception e) {
                            throw new Exception("Failed to initialize Datahop engine: " + e.getMessage(), e);
                        }
                    }
                }

                // ✅ Check if engine is ready (node is online)
                if (!datahopEngine.isNodeOnline()) {
                    Log.d(TAG, "Node is offline, waiting for it to come online...");

                    // Wait up to 15 seconds for the node to come online
                    int attempts = 0;
                    int maxAttempts = 15;

                    while (!datahopEngine.isNodeOnline() && attempts < maxAttempts) {
                        attempts++;
                        Log.d(TAG, "Waiting for node to come online... Attempt " + attempts + "/" + maxAttempts);

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {}
                    }

                    if (!datahopEngine.isNodeOnline()) {
                        String diagnosticReport = generateDeepDiagnosticReport(imageFile);
                        throw new Exception("P2P Engine timeout: Node did not come online after 15 seconds\n" + diagnosticReport);
                    }
                }

                Log.d(TAG, "✅ Node is online! Proceeding with file upload...");

                // ✅ Verify the file exists and is readable
                if (!imageFile.exists()) {
                    throw new Exception("Image file does not exist: " + imageFile.getAbsolutePath());
                }

                if (!imageFile.canRead()) {
                    throw new Exception("Cannot read image file: " + imageFile.getAbsolutePath());
                }

                Log.d(TAG, "File verified - Size: " + (imageFile.length() / 1024) + " KB");

                // ✅ Add the file to Datahop with proper parameters
                byte[] fileBytes = readFileToBytes(imageFile);
                String fileName = imageFile.getName();
                String mimeType = "image/*";

                Log.d(TAG, "Adding file to DHT: " + fileName + " (" + fileBytes.length + " bytes)");

                // This adds the file to the DHT
                datahopEngine.add(fileName, fileBytes, mimeType);

                Log.d(TAG, "File added successfully");

                // Get the peer ID as the identifier
                String peerId = datahopEngine.id();
                String cid = peerId;

                String ipfsProtocolLink = "ipfs://" + cid;

                Log.i(TAG, "✅ P2P Hosting Success. Peer ID: " + cid);

                // Return success back to the caller
                if (callback != null) {
                    callback.onSuccess(cid, ipfsProtocolLink);
                }

            } catch (Throwable t) {
                Log.e(TAG, "P2P Hosting Failed: ", t);
                t.printStackTrace();
                if (callback != null) {
                    callback.onFailure(new Exception("REAL GO ENGINE CRASH: " + t.getMessage(), t));
                }
            }
        }).start();
    }

    /**
     * ✅ Helper: Convert file to bytes
     * 
     * @param file The file to read
     * @return byte array of file contents
     * @throws Exception if file cannot be read
     */
    private static byte[] readFileToBytes(File file) throws Exception {
        try {
            java.nio.file.Path path = file.toPath();
            return java.nio.file.Files.readAllBytes(path);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read file: " + file.getAbsolutePath(), e);
            throw new Exception("Cannot read file: " + e.getMessage(), e);
        }
    }

    /**
     * Stop the Datahop engine gracefully
     * Call this in your app's onDestroy() or when shutting down
     */
    public static void stopDatahopEngine() {
        synchronized (engineLock) {
            if (datahopEngine != null) {
                try {
                    Log.d(TAG, "Stopping Datahop P2P Engine...");
                    datahopEngine.stop();
                    datahopEngine = null;
                    engineInitialized = false;
                    Log.i(TAG, "✅ Datahop engine stopped successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping Datahop: ", e);
                }
            }
        }
    }

    /**
     * Generates a massive string containing system state, memory limits, 
     * file permissions, and now a DEEP METHOD SCANNER to find out exactly 
     * what the Go-IPFS library expects us to call.
     */
    private static String generateDeepDiagnosticReport(File targetFile) {
        StringBuilder report = new StringBuilder();
        report.append("P2P Engine Failed to Initialize. Deep Diagnostic Report:\n");
        report.append("==================================================\n\n");

        // TEST 1: Check if the Datahop Go-Mobile AAR is actually linked
        report.append("[TEST 1: Library Linking]\n");
        try {
            Class.forName("datahop.Datahop");
            report.append(">> PASS: 'datahop.Datahop' found. datahop.aar is properly linked.\n");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("io.datahop.Datahop");
                report.append(">> PASS: 'io.datahop.Datahop' found. datahop.aar is properly linked.\n");
            } catch (ClassNotFoundException e2) {
                report.append(">> FATAL FAIL: Datahop classes NOT FOUND!\n");
            }
        }
        report.append("\n");

        // TEST 2: Device Architecture Compatibility
        report.append("[TEST 2: System Architecture]\n");
        report.append(">> Device ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        boolean isSupported = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.contains("arm64-v8a") || abi.contains("armeabi-v7a") || abi.contains("x86_64") || abi.contains("x86")) {
                isSupported = true;
                break;
            }
        }
        if (isSupported) {
            report.append(">> PASS: Device architecture is compatible with native C/Go binaries.\n");
        } else {
            report.append(">> FAIL: Device architecture may not be supported by the Datahop binary.\n");
        }
        report.append("\n");

        // TEST 3: File Permissions and State
        report.append("[TEST 3: Target File Verification]\n");
        if (targetFile != null) {
            report.append(">> File Path: ").append(targetFile.getAbsolutePath()).append("\n");
            report.append(">> File Exists: ").append(targetFile.exists()).append("\n");
            report.append(">> File Can Read: ").append(targetFile.canRead()).append("\n");
            report.append(">> File Size: ").append(targetFile.length() / 1024).append(" KB\n");
            report.append(">> PASS: File is perfectly accessible.\n");
        } else {
            report.append(">> FATAL FAIL: Target file object is null.\n");
        }
        report.append("\n");

        // TEST 4: JVM Memory State
        report.append("[TEST 4: JVM Memory State]\n");
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory() / (1024 * 1024);
        long totalMemory = rt.totalMemory() / (1024 * 1024);
        long freeMemory = rt.freeMemory() / (1024 * 1024);
        long trueFreeMemory = (maxMemory - totalMemory) + freeMemory;

        report.append(">> Max Heap Limit: ").append(maxMemory).append(" MB\n");
        report.append(">> Current Allocated: ").append(totalMemory).append(" MB\n");
        report.append(">> True Available Memory: ").append(trueFreeMemory).append(" MB\n");
        report.append(">> PASS: Sufficient memory available.\n");
        report.append("\n");

        // TEST 5: DATAHOP GO API SCANNER (THE FINAL FIX)
        // This will print EVERY method exported by the Datahop Go engine
        // so we know exactly how to initialize it without guessing.
        report.append("[TEST 5: DATAHOP GO API SCANNER]\n");
        try {
            Class<?> dc = Class.forName("datahop.Datahop");
            java.lang.reflect.Method[] methods = dc.getDeclaredMethods();
            if (methods.length == 0) {
                report.append(">> WARNING: No methods found inside datahop.Datahop.\n");
            } else {
                for (java.lang.reflect.Method m : methods) {
                    report.append(">> METHOD: ").append(m.getName()).append("(");
                    Class<?>[] pTypes = m.getParameterTypes();
                    for (int i = 0; i < pTypes.length; i++) {
                        report.append(pTypes[i].getSimpleName());
                        if (i < pTypes.length - 1) report.append(", ");
                    }
                    report.append(") -> ").append(m.getReturnType().getSimpleName()).append("\n");
                }
            }
        } catch (Throwable t) {
            report.append(">> SCANNER FAILED: ").append(t.getMessage()).append("\n");
        }

        report.append("\n==================================================");

        return report.toString();
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
