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
 * NIP-96 / Blossom Media Upload Engine.
 * Replaces the Datahop P2P system with standard HTTP uploads.
 * Handles the communication with public Nostr media relays.
 */
public class MediaUploadHelper {

    private static final String TAG = "AdNostr_MediaUpload";

    // CORE ENHANCEMENT: Hardcoded Default 5 Nostr Media Relays
    private static final String[] DEFAULT_SERVERS = {
            "https://nostr.build/api/v2/upload/files",
            "https://void.cat/api/v1/upload",
            "https://pixel.buzz/api/v1/upload",
            "https://nostrcheck.me/api/v2/upload",
            "https://files.sovbit.host/api/v1/upload"
    };

    private final OkHttpClient client;
    private final Handler mainHandler;

    /**
     * Interface for technical log reporting and result handling.
     */
    public interface MediaUploadCallback {
        void onStatusUpdate(String log);
        void onSuccess(String uploadedUrl, String deletionUrl);
        void onFailure(Exception e);
    }

    public MediaUploadHelper() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Uploads encrypted bytes to a media relay.
     * 
     * @param context    Needed for DB server check.
     * @param encryptedData The AES-GCM encrypted image bytes.
     * @param fileName   Pseudo filename (e.g., ad_image.enc).
     * @param callback   Reporting interface.
     */
    public void uploadEncryptedMedia(Context context, byte[] encryptedData, String fileName, MediaUploadCallback callback) {
        
        // 1. Determine Server (Custom or Default Pool)
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String customServer = db.getCustomMediaServer();
        String targetServer = (customServer != null && !customServer.isEmpty()) ? customServer : DEFAULT_SERVERS[0];

        callback.onStatusUpdate("TARGET SERVER: " + targetServer);
        Log.i(TAG, "Starting upload to: " + targetServer);

        // 2. Prepare Multipart Request
        RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("application/octet-stream"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build();

        Request request = new Request.Builder()
                .url(targetServer)
                .post(requestBody)
                .addHeader("User-Agent", "AdNostr-Android-App")
                .build();

        // 3. Execute Async Network Call
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String error = "Network Request Failed: " + e.getMessage();
                postLog(callback, "[HTTP FAIL] " + error);
                postFailure(callback, new Exception(error));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String rawResponse = response.body() != null ? response.body().string() : "";
                int code = response.code();

                postLog(callback, "HTTP STATUS: " + code);
                
                if (response.isSuccessful() && !rawResponse.isEmpty()) {
                    try {
                        // NIP-96 typical response structure:
                        // { "status": "success", "nip94_event": { "tags": [ ["url", "..."], ["ox", "..."] ] } }
                        // OR Blossom style: { "url": "...", "delete_url": "..." }
                        
                        JSONObject json = new JSONObject(rawResponse);
                        String uploadedUrl = "";
                        String deletionUrl = "";

                        if (json.has("url")) {
                            uploadedUrl = json.getString("url");
                        }
                        
                        // Handle Blossom deletion URL or token
                        if (json.has("delete_url")) {
                            deletionUrl = json.getString("delete_url");
                        } else if (json.has("deletion_token")) {
                            // Some servers use tokens; we construct the URL
                            deletionUrl = targetServer + "?token=" + json.getString("deletion_token");
                        }

                        if (uploadedUrl.isEmpty()) {
                            throw new Exception("Server response did not contain a valid media URL.");
                        }

                        postLog(callback, "[SUCCESS] Media hosted at: " + uploadedUrl);
                        postSuccess(callback, uploadedUrl, deletionUrl);

                    } catch (Exception e) {
                        String parseError = "Response Parse Error: " + e.getMessage() + "\nBody: " + rawResponse;
                        postLog(callback, "[PARSE FAIL] " + parseError);
                        postFailure(callback, new Exception(parseError));
                    }
                } else {
                    String serverError = "Server rejected upload (Code: " + code + ")\nResponse: " + rawResponse;
                    postLog(callback, "[SERVER ERROR] " + serverError);
                    postFailure(callback, new Exception(serverError));
                }
            }
        });
    }

    /**
     * Executes a deletion request for a media item.
     * 
     * @param deletionUrl The URL/Endpoint provided by the server during upload.
     */
    public void deleteMedia(String deletionUrl, MediaUploadCallback callback) {
        if (deletionUrl == null || deletionUrl.isEmpty()) return;

        Request request = new Request.Builder()
                .url(deletionUrl)
                .delete()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Deletion Request Failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.i(TAG, "Deletion response code: " + response.code());
            }
        });
    }

    // Helper methods to ensure UI updates happen on Main Thread
    private void postLog(MediaUploadCallback cb, String log) {
        mainHandler.post(() -> cb.onStatusUpdate(log));
    }

    private void postSuccess(MediaUploadCallback cb, String url, String delUrl) {
        mainHandler.post(() -> cb.onSuccess(url, delUrl));
    }

    private void postFailure(MediaUploadCallback cb, Exception e) {
        mainHandler.post(() -> cb.onFailure(e));
    }
}