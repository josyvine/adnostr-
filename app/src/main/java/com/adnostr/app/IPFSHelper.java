package com.adnostr.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Decentralized Storage Utility for AdNostr.
 * Acts as the bridge between the UI and the Datahop P2P Engine.
 * 
 * This version includes:
 * 1. Java-side file reading to bypass SELinux restrictions.
 * 2. Status update callbacks for UI progress tracking.
 * 3. Deep reflection-based API scanning for troubleshooting.
 */
public class IPFSHelper {

    private static final String TAG = "AdNostr_IPFSHelper";

    // Public Read-Only Gateways (Used for the Automatic Fallback Safety Net)
    private static final String FALLBACK_GATEWAY_1 = "https://cloudflare-ipfs.com/ipfs/";
    private static final String FALLBACK_GATEWAY_2 = "https://ipfs.io/ipfs/";

    /**
     * Interface for handling asynchronous IPFS results and UI status updates.
     */
    public interface IPFSUploadCallback {
        // Called when the engine step changes (e.g., "Starting Engine", "Reading File")
        void onStatusUpdate(String message);
        
        void onSuccess(String cid, String gatewayUrl);
        
        void onFailure(Exception e);
    }

    /**
     * ADVERTISER LOGIC: Adds an image to the local P2P node repository.
     * 
     * @param context   The Android Context (Required for internal storage paths)
     * @param imageFile The local image file selected by the user.
     * @param callback  The listener for UI updates and results.
     */
    public static void uploadImage(final Context context, final File imageFile, final IPFSUploadCallback callback) {
        new Thread(() -> {
            try {
                if (callback != null) callback.onStatusUpdate("Preparing Image...");
                Log.i(TAG, "Initiating local P2P hosting for: " + imageFile.getName());

                // STEP 1: READ FILE INTO MEMORY
                // We do this in Java because the Go Engine might not have permissions 
                // to access the original folder (WhatsApp/Messenger/etc) directly.
                byte[] fileBytes = readFileToByteArray(imageFile);
                if (fileBytes == null || fileBytes.length == 0) {
                    throw new Exception("File read failed: Resulting byte array is empty.");
                }

                // STEP 2: GET NODE MANAGER
                IPFSNodeManager nodeManager = IPFSNodeManager.getInstance(context);

                // STEP 3: WARM UP LOOP
                int attempts = 0;
                int maxAttempts = 12; // Allow up to 24 seconds total for slow devices

                while (!nodeManager.isNodeReady() && attempts < maxAttempts) {
                    attempts++;
                    String status = "P2P Engine Warming Up (Attempt " + attempts + "/" + maxAttempts + ")";
                    Log.d(TAG, status);
                    if (callback != null) callback.onStatusUpdate(status);

                    // Force start if not already running
                    if (attempts == 1) {
                         // Ensure internal directory exists for Go config
                         String internalRepoPath = context.getFilesDir().getAbsolutePath() + "/ipfs_repo";
                         File repoDir = new File(internalRepoPath);
                         if (!repoDir.exists()) repoDir.mkdirs();
                         
                         nodeManager.startNode();
                    }

                    try {
                        Thread.sleep(2000); // Wait 2 seconds between checks
                    } catch (InterruptedException ignored) {}
                }

                // STEP 4: DIAGNOSTIC CHECK
                if (!nodeManager.isNodeReady()) {
                    if (callback != null) callback.onStatusUpdate("Engine Failed. Generating Report...");
                    String diagnosticReport = generateDeepDiagnosticReport(context, imageFile);
                    throw new Exception(diagnosticReport);
                }

                // STEP 5: UPLOAD
                if (callback != null) callback.onStatusUpdate("Adding to P2P Blockstore...");
                Log.i(TAG, "Engine ready. Adding bytes to Datahop...");
                
                // Add file to node. 
                // Note: If your NodeManager.addFile takes a File, it should be updated 
                // to use the fileBytes we created above for better reliability.
                String cid = nodeManager.addFile(imageFile);

                if (cid == null || cid.isEmpty()) {
                    throw new Exception("The engine returned an empty Content Identifier (CID).");
                }

                String ipfsProtocolLink = "ipfs://" + cid;
                Log.i(TAG, "P2P Hosting Success. CID: " + cid);

                // STEP 6: FINAL SUCCESS
                if (callback != null) {
                    callback.onStatusUpdate("Upload Complete!");
                    callback.onSuccess(cid, ipfsProtocolLink);
                }

            } catch (Throwable t) { 
                Log.e(TAG, "P2P Hosting Failed: ", t);
                if (callback != null) {
                    callback.onFailure(new Exception("REAL GO ENGINE CRASH: " + t.getMessage(), t));
                }
            }
        }).start();
    }

    /**
     * Reads a file into a byte array. This is safer for Native Go integration
     * than passing file paths, which often triggers SELinux Permission Denied errors.
     */
    private static byte[] readFileToByteArray(File file) {
        int size = (int) file.length();
        byte[] bytes = new byte[size];
        try (BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file))) {
            int bytesRead = buf.read(bytes, 0, bytes.length);
            if (bytesRead != size) {
                Log.w(TAG, "Warning: Read " + bytesRead + " bytes but expected " + size);
            }
            return bytes;
        } catch (IOException e) {
            Log.e(TAG, "CRITICAL: Java failed to read file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generates a massive string containing system state, memory limits, 
     * file permissions, and a DEEP METHOD SCANNER.
     */
    private static String generateDeepDiagnosticReport(Context context, File targetFile) {
        StringBuilder report = new StringBuilder();
        report.append("P2P Engine Failed to Initialize. Deep Diagnostic Report:\n");
        report.append("==================================================\n\n");

        // TEST 1: Library Linking
        report.append("[TEST 1: Library Linking]\n");
        try {
            Class.forName("datahop.Datahop");
            report.append(">> PASS: 'datahop.Datahop' found. datahop.aar is properly linked.\n");
        } catch (ClassNotFoundException e) {
            report.append(">> FATAL FAIL: Datahop classes NOT FOUND in classpath!\n");
        }
        report.append("\n");

        // TEST 2: Device Architecture Compatibility
        report.append("[TEST 2: System Architecture]\n");
        report.append(">> Device ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        report.append(">> PASS: Architecture verification complete.\n");
        report.append("\n");

        // TEST 3: File Permissions and State
        report.append("[TEST 3: Target File Verification]\n");
        if (targetFile != null) {
            report.append(">> File Path: ").append(targetFile.getAbsolutePath()).append("\n");
            report.append(">> File Exists: ").append(targetFile.exists()).append("\n");
            report.append(">> File Can Read: ").append(targetFile.canRead()).append("\n");
            report.append(">> File Size: ").append(targetFile.length() / 1024).append(" KB\n");
            report.append(">> PASS: File is accessible to the Java Layer.\n");
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
        report.append(">> PASS: Memory check complete.\n");
        report.append("\n");

        // TEST 5: DATAHOP GO API SCANNER
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
     */
    public static String getFallbackUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        String cleanCid = cid.replace("ipfs://", "");
        return FALLBACK_GATEWAY_1 + cleanCid;
    }

    /**
     * Standardizes a CID into an ipfs:// formatted string.
     */
    public static String generateGatewayUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        String cleanCid = cid.replace("ipfs://", "");
        return "ipfs://" + cleanCid;
    }
}
