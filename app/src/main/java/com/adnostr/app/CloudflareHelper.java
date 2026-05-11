package com.adnostr.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
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
 * ENHANCEMENT: Added JSON file upload/download for Feature 5 (Marketplace).
 * 
 * FIXED: Deletion logic now extracts clean IDs from full URLs to ensure R2 physical wipe success.
 * 
 * NEW ENHANCEMENT: 5-LAYER DATABASE ARCHITECT ENGINE
 * - Added X-AdNostr-Token Handshake for Hierarchical Database Fetching.
 * - Added Multipart Batch Stream for ASIN_RAW mass uploads.
 * - Added Administrative Seeding logic (Hierarchy Anchors).
 * 
 * GLITCH FIX: 
 * - Increased write/read timeouts to 120s to prevent "Worker rejected batch - timeout" on large Samsung batches.
 * - Added body preparation tracing to keep Forensic Terminal alive.
 * 
 * BATCH ARCHITECT FIX (SOCKET ISOLATION):
 * - Implemented 'Connection: close' header for batch streams to prevent 500 Parser Desync on subsequent chunks.
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

    /**
     * Specialized callback for downloading product JSON data.
     */
    public interface JsonDownloadCallback {
        void onDownloadSuccess(String jsonContent);
        void onDownloadFailure(Exception e);
    }

    public CloudflareHelper() {
        // High-performance client with optimized timeouts for direct private connection
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS) // GLITCH FIX: Increased for 20-file chunks
                .readTimeout(60, TimeUnit.SECONDS)   // GLITCH FIX: Increased for Worker Indexing lag
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
     * FEATURE 5: Uploads a raw JSON string as a file to the Cloudflare Worker.
     */
    public void uploadJsonFile(Context context, String jsonContent, String fileName, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String workerUrl = db.getCloudflareWorkerUrl();
        String secretToken = db.getCloudflareSecretToken();

        if (workerUrl == null || workerUrl.isEmpty()) {
            postFailure(callback, new Exception("Worker URL missing."));
            return;
        }

        try {
            RequestBody fileBody = RequestBody.create(jsonContent, MediaType.parse("application/json; charset=utf-8"));
            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(workerUrl)
                    .post(requestBody)
                    .addHeader("Secret-Token", secretToken)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postFailure(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String rawBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        try {
                            JSONObject result = new JSONObject(rawBody);
                            postSuccess(callback, result.getString("url"), result.optString("id", ""));
                        } catch (Exception e) {
                            postFailure(callback, e);
                        }
                    } else {
                        postFailure(callback, new Exception("Upload Rejected: " + response.code()));
                    }
                }
            });

        } catch (Exception e) {
            postFailure(callback, e);
        }
    }

    /**
     * FEATURE 5: Fetches a JSON file from a public Cloudflare URL.
     */
    public void downloadJsonFile(String url, JsonDownloadCallback callback) {
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) mainHandler.post(() -> callback.onDownloadFailure(e));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String content = response.body().string();
                    if (callback != null) mainHandler.post(() -> callback.onDownloadSuccess(content));
                } else {
                    if (callback != null) mainHandler.post(() -> 
                        callback.onDownloadFailure(new Exception("Download Error: " + response.code()))
                    );
                }
            }
        });
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

        String cleanFileId = fileIdOrUrl;
        if (fileIdOrUrl.contains("/")) {
            cleanFileId = fileIdOrUrl.substring(fileIdOrUrl.lastIndexOf("/") + 1);
        }

        postLog(callback, "=== INITIATING CLOUDFLARE PHYSICAL WIPE ===\n");
        postLog(callback, "SOURCE REF: " + fileIdOrUrl + "\n");
        postLog(callback, "CLEAN TARGET ID: " + cleanFileId + "\n");

        String baseWorker = workerUrl.endsWith("/") ? workerUrl.substring(0, workerUrl.length() - 1) : workerUrl;
        String deleteEndpoint = baseWorker + "?id=" + cleanFileId;

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
                String rawBody = response.body() != null ? response.body().string() : "No body";
                
                if (response.isSuccessful()) {
                    postLog(callback, "Cloudflare Wipe Success (HTTP " + code + ")\n");
                    postLog(callback, "STORAGE FREED: Resource removed from R2 Bucket.\n");
                } else {
                    postLog(callback, "!!! CLOUDFLARE WIPE REJECTED (HTTP " + code + ")\n");
                    postLog(callback, "SERVER REASON: " + rawBody + "\n");
                }
            }
        });
    }

    // =========================================================================
    // NEW: 5-LAYER DATABASE ARCHITECT METHODS
    // =========================================================================

    /**
     * SECURE FETCH: Retrieves a specific JSON layer using the X-AdNostr-Token handshake.
     */
    public void fetchSchemaLayer(Context context, String path, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String baseUrl = db.getDbQueryUrl();
        String apiToken = db.getDbApiToken();

        if (baseUrl.isEmpty()) {
            postFailure(callback, new Exception("Database URL not configured."));
            return;
        }

        String fullUrl = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;

        Request request = new Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("X-AdNostr-Token", apiToken)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) { postFailure(callback, e); }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "[]";
                if (response.isSuccessful()) {
                    postSuccess(callback, body, path);
                } else {
                    postFailure(callback, new Exception("Fetch Rejected: " + response.code()));
                }
            }
        });
    }

    /**
     * BATCH UPLOAD: Streams multiple JSON files to R2 via MultipartBody.
     * Implements X-Admin-Sig (BIP-340) and X-Target-Path headers.
     * 
     * GLITCH FIX: Added trace logging for Body preparation to keep the terminal alive.
     * 
     * BATCH ARCHITECT FIX: Added 'Connection: close' to ensure chunk isolation.
     */
    public void uploadBatchToR2(Context context, List<Uri> uris, String path, String signature, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String baseUrl = db.getDbQueryUrl();
        String apiToken = db.getDbApiToken();

        postLog(callback, "=== STARTING BATCH MULTIPART STREAM ===\n");
        postLog(callback, "TARGET: " + path + "\n");

        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                String fileName = getFileNameFromUri(context, uri);
                byte[] fileData = readBytesFromUri(context, uri);
                if (fileData != null) {
                    postLog(callback, "PREPARING: [" + (i + 1) + "/" + uris.size() + "] " + fileName + "\n");
                    builder.addFormDataPart("files", fileName, 
                            RequestBody.create(fileData, MediaType.parse("application/json")));
                }
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/batch")
                    .post(builder.build())
                    .addHeader("X-AdNostr-Token", apiToken)
                    .addHeader("X-Admin-Sig", signature)
                    .addHeader("X-Target-Path", path)
                    .addHeader("Connection", "close") // THE FIX: Forces fresh socket per chunk
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) { 
                    if (e instanceof SocketTimeoutException) {
                        postLog(callback, "!!! NETWORK TIMEOUT: Worker took too long to respond. Increase CHUNK_SIZE or check Worker tier.\n");
                    }
                    postFailure(callback, e); 
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String res = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        postLog(callback, "WORKER: Batch processing successful.\n");
                        postSuccess(callback, res, "BATCH_OK");
                    } else {
                        // LOG RAW ERROR: Captures plain-text 500/504 errors without crashing the parser
                        postFailure(callback, new Exception("Batch Failed: " + response.code() + " " + res));
                    }
                }
            });

        } catch (Exception e) {
            postFailure(callback, e);
        }
    }

    /**
     * BOOTSTRAP SEEDING: Broadcasts a Single-Point anchor to create a new Category/Brand index.
     */
    public void broadcastHierarchyAnchor(Context context, String tier, String name, String path, String signature, CloudflareCallback callback) {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        
        postLog(callback, "SEEDING: " + tier.toUpperCase() + " [" + name + "]\n");

        try {
            JSONObject payload = new JSONObject();
            payload.put("tier", tier);
            payload.put("name", name);
            payload.put("path", path);

            RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(db.getDbQueryUrl() + "/v1/seed")
                    .post(body)
                    .addHeader("X-AdNostr-Token", db.getDbApiToken())
                    .addHeader("X-Admin-Sig", signature)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) { postFailure(callback, e); }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        postLog(callback, "SEED SUCCESS: Index anchor created on R2.\n");
                        postSuccess(callback, name, tier);
                    } else {
                        postFailure(callback, new Exception("Seed Rejected: " + response.code()));
                    }
                }
            });
        } catch (Exception e) {
            postFailure(callback, e);
        }
    }

    // =========================================================================
    // IO UTILITIES
    // =========================================================================

    private byte[] readBytesFromUri(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            return bos.toByteArray();
        } catch (Exception e) { return null; }
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) result = cursor.getString(nameIndex);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    // =========================================================================
    // UI THREAD POSTING HELPERS (Keeps logic clean)
    // =========================================================================

    private void postLog(CloudflareCallback cb, String log) {
        if (cb != null) mainHandler.post(() -> cb.onStatusUpdate(log));
    }

    private void postSuccess(CloudflareCallback cb, String url, String extra) {
        if (cb != null) mainHandler.post(() -> cb.onSuccess(url, extra));
    }

    private void postFailure(CloudflareCallback cb, Exception e) {
        if (cb != null) mainHandler.post(() -> cb.onFailure(e));
    }
}