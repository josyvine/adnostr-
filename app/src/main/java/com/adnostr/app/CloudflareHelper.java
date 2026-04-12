package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * PRIVATE CLOUDFLARE STORAGE ENGINE
 * Replaces Blossom/NIP-96 discovery with direct Advertiser-owned infrastructure.
 * FEATURE: Local AES-GCM binary upload to private Cloudflare Worker.
 * FEATURE: Auth via Secret-Token provided in Advertiser Settings.
 * FEATURE: Single-click physical wipe (HTTP DELETE) logic.
 * FEATURE: Detailed forensic logging for the Technical Network Console.
 */
public class CloudflareHelper {

    private static final String TAG = "AdNostr_Cloudflare";
    private final OkHttpClient client;
    private final Handler mainHandler;

    /**
     * Interface to communicate progress and forensic data back to the Fragment UI.
     */
    public interface CloudflareCallback {
        void onStatusUpdate(String log);
        void onSuccess(String uploadedUrl, String fileId);
        void onFailure(Exception e);
    }

    public CloudflareHelper() {
        // High-performance client with optimized timeouts for direct private connection
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * UPLOAD LOGIC: Sends the locally encrypted binary blob to the Cloudflare Worker.
     * Uses the Secret-Token header for private authentication.
     */
    public void uploadMedia(Context context, byte[] encryptedData, String fileName, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String workerUrl = db.getCloudflareWorkerUrl();
        String secretToken = db.getCloudflareSecretToken();

        // 1. Validate Credentials
        if (workerUrl == null || workerUrl.isEmpty()) {
            postFailure(callback, new Exception("CLOUDFLARE ERROR: Worker URL not configured in Settings."));
            return;
        }

        postLog(callback, "=== INITIATING PRIVATE CLOUDFLARE UPLOAD ===\n");
        postLog(callback, "ENDPOINT: " + workerUrl + "\n");
        postLog(callback, "AUTH: Secret-Token identified.\n");
        postLog(callback, "PAYLOAD: AES-GCM Encrypted Blob Ready.\n");

        try {
            // 2. Build Multipart Body (Encrypted bytes masqueraded as binary data)
            RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("application/octet-stream"));
            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody)
                    .build();

            // 3. Construct Request with Private Auth Header
            Request request = new Request.Builder()
                    .url(workerUrl)
                    .post(requestBody)
                    .addHeader("Secret-Token", secretToken)
                    .addHeader("User-Agent", "AdNostr-Private-Cloud-Agent/1.0")
                    .build();

            // 4. Execute Private Tunnel
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postLog(callback, "!!! NETWORK FAILURE: Could not reach Worker. Check URL or Internet Connection.\n");
                    postFailure(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String rawBody = response.body() != null ? response.body().string() : "";
                    int httpCode = response.code();

                    postLog(callback, "HTTP STATUS: " + httpCode + "\n");

                    if (response.isSuccessful()) {
                        try {
                            // Expecting Cloudflare Worker to return {"url": "...", "id": "..."}
                            JSONObject result = new JSONObject(rawBody);
                            String publicUrl = result.getString("url");
                            String fileId = result.optString("id", publicUrl); // Use URL as ID if ID is missing

                            postLog(callback, "[SUCCESS] Private Storage Locked.\n");
                            postLog(callback, "PUBLIC LINK: " + publicUrl + "\n");
                            postSuccess(callback, publicUrl, fileId);

                        } catch (Exception e) {
                            postLog(callback, "!!! DATA ERROR: Worker returned invalid JSON: " + rawBody + "\n");
                            postFailure(callback, new Exception("JSON Parse Error: " + e.getMessage()));
                        }
                    } else {
                        postLog(callback, "!!! WORKER REJECTION (HTTP " + httpCode + "): Check Secret-Token and Worker Logic.\n");
                        postLog(callback, "RESPONSE: " + rawBody + "\n");
                        postFailure(callback, new Exception("Cloudflare Rejection: " + httpCode));
                    }
                }
            });

        } catch (Exception e) {
            postLog(callback, "!!! PREPARATION ERROR: " + e.getMessage() + "\n");
            postFailure(callback, e);
        }
    }

    /**
     * DELETION LOGIC: Performs a physical wipe from the Advertiser's R2 Bucket.
     * Authenticates via Secret-Token to ensure only the owner can delete.
     */
    public void deleteMedia(Context context, String fileIdOrUrl, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String workerUrl = db.getCloudflareWorkerUrl();
        String secretToken = db.getCloudflareSecretToken();

        if (workerUrl == null || workerUrl.isEmpty()) {
            postLog(callback, "DELETION ABORTED: Private Worker URL missing.\n");
            return;
        }

        postLog(callback, "=== INITIATING CLOUDFLARE PHYSICAL WIPE ===\n");
        postLog(callback, "TARGET ID: " + fileIdOrUrl + "\n");

        // The Worker logic usually expects the file ID as a query param or part of the URL
        // We will append it to the URL or include it in the header based on standard Worker patterns
        String deleteEndpoint = workerUrl;
        if (!workerUrl.contains("?")) {
            deleteEndpoint += "?id=" + fileIdOrUrl;
        } else {
            deleteEndpoint += "&id=" + fileIdOrUrl;
        }

        Request request = new Request.Builder()
                .url(deleteEndpoint)
                .delete()
                .addHeader("Secret-Token", secretToken)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postLog(callback, "!!! WIPE FAILED: Network error during physical deletion.\n");
                if (callback != null) callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                if (response.isSuccessful()) {
                    postLog(callback, "Cloudflare Wipe Success (HTTP " + code + ")\n");
                    postLog(callback, "STORAGE FREED: Resource removed from R2 Bucket.\n");
                } else {
                    postLog(callback, "!!! CLOUDFLARE WIPE REJECTED (HTTP " + code + "): Check Token permissions.\n");
                }
            }
        });
    }

    // =========================================================================
    // UI THREAD POSTING HELPERS (Keeps logic clean)
    // =========================================================================

    private void postLog(CloudflareCallback cb, String log) {
        if (cb != null) mainHandler.post(() -> cb.onStatusUpdate(log));
    }

    private void postSuccess(CloudflareCallback cb, String url, String fileId) {
        if (cb != null) mainHandler.post(() -> cb.onSuccess(url, fileId));
    }

    private void postFailure(CloudflareCallback cb, Exception e) {
        if (cb != null) mainHandler.post(() -> cb.onFailure(e));
    }
}