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
 * UPDATED: Implemented Server Rotation Fallback to bypass 500 Internal Server Errors.
 * UPDATED: Uses Image-Mime masquerading to prevent server-side processing crashes.
 * UPDATED: Includes NIP-98 Authentication for "No-Signup" anonymous uploads and deletions.
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
     * Entry point for encrypted media upload.
     */
    public void uploadEncryptedMedia(Context context, byte[] encryptedData, String fileName, MediaUploadCallback callback) {
        // Start the recursive upload attempt beginning with server index 0
        attemptUploadWithFallback(context, encryptedData, 0, callback);
    }

    /**
     * Logic to rotate through servers if a 500 error or connection failure occurs.
     */
    private void attemptUploadWithFallback(Context context, byte[] encryptedData, int serverIndex, MediaUploadCallback callback) {
        // 1. Check if we have exhausted all servers
        if (serverIndex >= DEFAULT_SERVERS.length) {
            postFailure(callback, new Exception("All available media servers failed (HTTP 500/401). Please check your internet connection."));
            return;
        }

        // 2. Identify target (Custom server first, then the default list)
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String customServer = db.getCustomMediaServer();
        String targetServer = (serverIndex == 0 && customServer != null && !customServer.isEmpty()) 
                ? customServer : DEFAULT_SERVERS[serverIndex];

        postLog(callback, "ATTEMPTING UPLOAD (" + (serverIndex + 1) + "/" + DEFAULT_SERVERS.length + "): " + targetServer);
        Log.i(TAG, "Attempting upload to: " + targetServer);

        // 3. Generate NIP-98 Authorization Token for this specific server
        String authHeader = "";
        try {
            authHeader = generateNip98Header(context, targetServer, "POST", encryptedData);
        } catch (Exception e) {
            Log.e(TAG, "NIP-98 generation failed for " + targetServer + ": " + e.getMessage());
        }

        // 4. Prepare Multipart Request
        // CRITICAL FIX: Use image/jpeg masquerading to bypass server-side optimize filters which cause 500 errors.
        RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("image/jpeg"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "image.jpg", fileBody)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(targetServer)
                .post(requestBody)
                .addHeader("User-Agent", "AdNostr-Android-App");

        if (!authHeader.isEmpty()) {
            requestBuilder.addHeader("Authorization", authHeader);
        }

        // 5. Execute Call
        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postLog(callback, "Server " + (serverIndex + 1) + " connection failed. Trying next node...");
                attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String rawResponse = response.body() != null ? response.body().string() : "";
                int code = response.code();

                if (response.isSuccessful() && !rawResponse.isEmpty()) {
                    try {
                        JSONObject json = new JSONObject(rawResponse);
                        String uploadedUrl = "";
                        String deletionUrl = "";

                        // Parse standard Blossom/NIP-96 JSON responses
                        if (json.has("url")) {
                            uploadedUrl = json.getString("url");
                        } else if (json.has("nip94_event")) {
                            JSONArray tags = json.getJSONObject("nip94_event").getJSONArray("tags");
                            for (int i = 0; i < tags.length(); i++) {
                                JSONArray tag = tags.getJSONArray(i);
                                if (tag.length() >= 2 && "url".equals(tag.getString(0))) {
                                    uploadedUrl = tag.getString(1);
                                }
                            }
                        }

                        if (json.has("delete_url")) {
                            deletionUrl = json.getString("delete_url");
                        } else if (json.has("deletion_token")) {
                            deletionUrl = targetServer + "?token=" + json.getString("deletion_token");
                        }

                        if (uploadedUrl.isEmpty()) {
                            throw new Exception("Response contained no URL.");
                        }

                        postLog(callback, "[SUCCESS] Media hosted successfully.");
                        postSuccess(callback, uploadedUrl, deletionUrl);

                    } catch (Exception e) {
                        postLog(callback, "Server " + (serverIndex + 1) + " parse error. Rotating to fallback...");
                        attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                    }
                } else {
                    // This handles 500, 413 (File too large), or 403 errors by rotating
                    postLog(callback, "Server " + (serverIndex + 1) + " returned HTTP " + code + ". Rotating to fallback...");
                    attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                }
            }
        });
    }

    /**
     * Executes a deletion request with NIP-98 authentication.
     */
    public void deleteMedia(Context context, String deletionUrl, MediaUploadCallback callback) {
        if (deletionUrl == null || deletionUrl.isEmpty()) return;

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

        client.newCall(builder.build()).enqueue(new Callback() {
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

        JSONObject event = new JSONObject();
        event.put("kind", 27235);
        event.put("pubkey", pubKey);
        event.put("created_at", System.currentTimeMillis() / 1000);
        event.put("content", "");

        JSONArray tags = new JSONArray();
        tags.put(new JSONArray().put("u").put(url));
        tags.put(new JSONArray().put("method").put(method));

        if (payload != null) {
            tags.put(new JSONArray().put("payload").put(calculateSha256(payload)));
        }

        event.put("tags", tags);

        JSONObject signedEvent = NostrEventSigner.signEvent(privKey, event);
        if (signedEvent == null) throw new Exception("Signing failed.");

        String base64Event = Base64.encodeToString(signedEvent.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
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