package com.adnostr.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * UPDATED: Implemented NIP-98 Authentication to fix 401 Unauthorized errors.
 * This allows "No-Signup" anonymous uploads by signing the HTTP request 
 * with the user's existing Nostr Private Key.
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
     * Uploads encrypted bytes to a media relay with NIP-98 Authentication.
     */
    public void uploadEncryptedMedia(Context context, byte[] encryptedData, String fileName, MediaUploadCallback callback) {

        // 1. Determine Server (Custom or Default Pool)
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String customServer = db.getCustomMediaServer();
        String targetServer = (customServer != null && !customServer.isEmpty()) ? customServer : DEFAULT_SERVERS[0];

        callback.onStatusUpdate("TARGET SERVER: " + targetServer);
        Log.i(TAG, "Starting upload to: " + targetServer);

        // 2. Generate NIP-98 Authorization Token (The "No-Signup" Fix)
        String authHeader = "";
        try {
            authHeader = generateNip98Header(context, targetServer, "POST", encryptedData);
            callback.onStatusUpdate("[NIP-98] Auth Token Generated Successfully.");
        } catch (Exception e) {
            callback.onStatusUpdate("[NIP-98 ERR] Token generation failed: " + e.getMessage());
        }

        // 3. Prepare Multipart Request
        RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("application/octet-stream"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(targetServer)
                .post(requestBody)
                .addHeader("User-Agent", "AdNostr-Android-App");

        // Attach NIP-98 Token if generated
        if (!authHeader.isEmpty()) {
            requestBuilder.addHeader("Authorization", authHeader);
        }

        Request request = requestBuilder.build();

        // 4. Execute Async Network Call
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
                        JSONObject json = new JSONObject(rawResponse);
                        String uploadedUrl = "";
                        String deletionUrl = "";

                        // Handle various NIP-96/Blossom response formats
                        if (json.has("url")) {
                            uploadedUrl = json.getString("url");
                        } else if (json.has("nip94_event")) {
                            JSONArray tags = json.getJSONObject("nip94_event").getJSONArray("tags");
                            for (int i = 0; i < tags.length(); i++) {
                                JSONArray tag = tags.getJSONArray(i);
                                if (tag.getString(0).equals("url")) uploadedUrl = tag.getString(1);
                            }
                        }

                        if (json.has("delete_url")) {
                            deletionUrl = json.getString("delete_url");
                        } else if (json.has("deletion_token")) {
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
     * Executes a deletion request with NIP-98 authentication.
     */
    public void deleteMedia(Context context, String deletionUrl, MediaUploadCallback callback) {
        if (deletionUrl == null || deletionUrl.isEmpty()) return;

        // Generate NIP-98 Token for DELETE method
        String authHeader = "";
        try {
            authHeader = generateNip98Header(context, deletionUrl, "DELETE", null);
        } catch (Exception e) {
            Log.e(TAG, "NIP-98 DELETE token failed: " + e.getMessage());
        }

        Request.Builder builder = new Request.Builder()
                .url(deletionUrl)
                .delete();

        if (!authHeader.isEmpty()) {
            builder.addHeader("Authorization", authHeader);
        }

        Request request = builder.build();

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

    /**
     * Generates a NIP-98 Authorization header: "Nostr <base64_event>"
     */
    private String generateNip98Header(Context context, String url, String method, byte[] payload) throws Exception {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String privKey = db.getPrivateKey();
        String pubKey = db.getPublicKey();

        if (privKey == null || pubKey == null) throw new Exception("Identity keys missing.");

        // Create NIP-98 Kind 27235 Event
        JSONObject event = new JSONObject();
        event.put("kind", 27235);
        event.put("pubkey", pubKey);
        event.put("created_at", System.currentTimeMillis() / 1000);
        event.put("content", "");

        JSONArray tags = new JSONArray();
        
        // Tag u: Target URL
        JSONArray uTag = new JSONArray();
        uTag.put("u");
        uTag.put(url);
        tags.put(uTag);

        // Tag method: HTTP Method
        JSONArray mTag = new JSONArray();
        mTag.put("method");
        mTag.put(method);
        tags.put(mTag);

        // Tag payload: SHA-256 of the body (Required for POST)
        if (payload != null) {
            JSONArray pTag = new JSONArray();
            pTag.put("payload");
            pTag.put(calculateSha256(payload));
            tags.put(pTag);
        }

        event.put("tags", tags);

        // Sign the event using the app's existing Nostr Signer
        JSONObject signedEvent = NostrEventSigner.signEvent(privKey, event);
        if (signedEvent == null) throw new Exception("Signing failed.");

        // Base64 encode the signed JSON
        String jsonString = signedEvent.toString();
        String base64Event = Base64.encodeToString(jsonString.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        return "Nostr " + base64Event;
    }

    /**
     * Calculates SHA-256 hex string for NIP-98 payload tag.
     */
    private String calculateSha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

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