package com.adnostr.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

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
     * FIXED: Includes Deep Diagnostic Engine to capture exact reasons for Engine failure.
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

                // 2. WARM UP LOOP (Wait up to 15 seconds)
                int attempts = 0;
                int maxAttempts = 15; 

                while (!nodeManager.isNodeReady() && attempts < maxAttempts) {
                    attempts++;
                    Log.d(TAG, "P2P Engine status: WARMING UP (Attempt " + attempts + "/" + maxAttempts + ")");

                    // Attempt to kickstart the node if it's dead
                    if (attempts == 1) {
                         nodeManager.startNode();
                    }

                    try {
                        Thread.sleep(1000); 
                    } catch (InterruptedException ignored) {}
                }

                // 3. IF STILL NOT READY -> GENERATE DEEP DIAGNOSTIC REPORT
                if (!nodeManager.isNodeReady()) {
                    String diagnosticReport = generateDeepDiagnosticReport(imageFile);
                    throw new Exception(diagnosticReport);
                }

                // 4. Add the file to the local P2P blockstore
                String cid = nodeManager.addFile(imageFile);

                // 5. Construct the protocol link for Nostr JSON
                String ipfsProtocolLink = "ipfs://" + cid;

                Log.i(TAG, "P2P Hosting Success. CID: " + cid);

                // 6. Return success back to the CreateAdFragment
                if (callback != null) {
                    callback.onSuccess(cid, ipfsProtocolLink);
                }

            } catch (Throwable t) { // CHANGED: Catch Throwable to capture OutOfMemoryError and NoClassDefFoundError
                Log.e(TAG, "P2P Hosting Failed: ", t);
                t.printStackTrace();
                if (callback != null) {
                    // Wrap the Throwable in an Exception so the callback can handle it
                    callback.onFailure(new Exception("REAL GO ENGINE CRASH: " + t.getMessage(), t));
                }
            }
        }).start();
    }

    /**
     * Generates a massive string containing system state, memory limits, 
     * file permissions, and classpath checks to find out exactly why the 
     * Go-IPFS library failed to initialize.
     */
    private static String generateDeepDiagnosticReport(File targetFile) {
        StringBuilder report = new StringBuilder();
        report.append("P2P Engine Failed to Initialize. Deep Diagnostic Report:\n");
        report.append("==================================================\n\n");

        // TEST 1: Check if the Datahop Go-Mobile AAR is actually linked
        report.append("[TEST 1: Library Linking]\n");
        try {
            Class.forName("mobile.Mobile");
            report.append(">> PASS: 'mobile.Mobile' found. datahop.aar is properly linked.\n");
        } catch (ClassNotFoundException e) {
            report.append(">> FATAL FAIL: 'mobile.Mobile' NOT FOUND!\n");
            report.append(">> REASON: The datahop.aar library was not compiled into the APK. Check build.gradle 'implementation files(\"libs/datahop.aar\")'\n");
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

            if (!targetFile.exists() || !targetFile.canRead()) {
                report.append(">> FAIL: The app does not have permission to read the selected file, or the file path is a virtual URI.\n");
            } else {
                report.append(">> PASS: File is perfectly accessible.\n");
            }
        } else {
            report.append(">> FATAL FAIL: Target file object is null.\n");
        }
        report.append("\n");

        // TEST 4: JVM Memory State (OutOfMemory check)
        report.append("[TEST 4: JVM Memory State]\n");
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory() / (1024 * 1024);
        long freeMemory = rt.freeMemory() / (1024 * 1024);
        long totalMemory = rt.totalMemory() / (1024 * 1024);
        report.append(">> Max Memory: ").append(maxMemory).append(" MB\n");
        report.append(">> Total Allocated: ").append(totalMemory).append(" MB\n");
        report.append(">> Free Memory: ").append(freeMemory).append(" MB\n");
        if (freeMemory < 10) {
            report.append(">> WARNING: Very low free memory. The Go-routine may have crashed due to OOM.\n");
        } else {
            report.append(">> PASS: Sufficient memory available.\n");
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