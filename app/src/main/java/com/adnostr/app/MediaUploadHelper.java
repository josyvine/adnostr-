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
 * FORENSIC DIAGNOSTIC VERSION: Produces detailed logs for debugging 500/401 errors.
 */
public class MediaUploadHelper {

    private static final String TAG = "AdNostr_MediaForensics";

    private static final String[] DEFAULT_SERVERS = {
            "https://nostr.build/api/v2/upload/files",
            "https://void.cat/api/v1/upload",
            "https://pixel.buzz/api/v1/upload",
            "https://nostrcheck.me/api/v2/upload",
            "https://files.sovbit.host/api/v1/upload"
    };

    private final OkHttpClient client;
    private final Handler mainHandler;

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

    public void uploadEncryptedMedia(Context context, byte[] encryptedData, String fileName, MediaUploadCallback callback) {
        attemptUploadWithFallback(context, encryptedData, 0, callback);
    }

    /**
     * Diagnostic Fallback Loop.
     */
    private void attemptUploadWithFallback(Context context, byte[] encryptedData, int serverIndex, MediaUploadCallback callback) {
        if (serverIndex >= DEFAULT_SERVERS.length) {
            postFailure(callback, new Exception("FORENSIC REPORT: All 5 nodes rejected the request. Inspect the logs above for 'RAW SERVER BODY' to find the rejection reason."));
            return;
        }

        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String customServer = db.getCustomMediaServer();
        String targetServer = (serverIndex == 0 && customServer != null && !customServer.isEmpty()) 
                ? customServer : DEFAULT_SERVERS[serverIndex];

        // START FORENSIC LOG ACCUMULATION
        StringBuilder forensicLog = new StringBuilder();
        forensicLog.append("=== INITIATING FORENSIC UPLOAD attempt ").append(serverIndex + 1).append(" ===\n");
        forensicLog.append("URL: ").append(targetServer).append("\n");

        try {
            // 1. Calculate SHA256 of the encrypted blob for NIP-98
            String payloadHash = calculateSha256(encryptedData);
            forensicLog.append("ENCRYPTED PAYLOAD HASH: ").append(payloadHash).append("\n");

            // 2. Generate and Log Raw NIP-98 Event
            JSONObject n98Event = createNip98Event(context, targetServer, "POST", payloadHash);
            forensicLog.append("RAW NIP-98 JSON (PRE-SIGN):\n").append(n98Event.toString(2)).append("\n");

            // 3. Sign and Base64 Encode
            JSONObject signedN98 = NostrEventSigner.signEvent(db.getPrivateKey(), n98Event);
            if (signedN98 == null) throw new Exception("Nostr Signer returned NULL event.");
            
            String base64Auth = Base64.encodeToString(signedN98.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String authHeader = "Nostr " + base64Auth;
            forensicLog.append("BIP-340 SIGNATURE: ").append(signedN98.getString("sig")).append("\n");

            // 4. Prepare Multipart with strict MIME
            RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("image/jpeg"));
            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "image.jpg", fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(targetServer)
                    .post(requestBody)
                    .addHeader("Authorization", authHeader)
                    .addHeader("User-Agent", "AdNostr-Forensic-Client/1.1")
                    .build();

            forensicLog.append("REQUEST HEADERS:\n").append(request.headers().toString()).append("\n");
            
            // Push diagnostic chunk to UI Console
            postLog(callback, forensicLog.toString());

            // 5. Execute Async Request
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postLog(callback, "!!! NETWORK FAILURE (Server " + (serverIndex + 1) + "): " + e.getMessage());
                    attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String rawBody = response.body() != null ? response.body().string() : "EMPTY_RESPONSE_BODY";
                    int code = response.code();

                    StringBuilder responseLog = new StringBuilder();
                    responseLog.append("--- SERVER RESPONSE (Node ").append(serverIndex + 1).append(") ---\n");
                    responseLog.append("HTTP CODE: ").append(code).append("\n");
                    responseLog.append("RAW SERVER BODY:\n").append(rawBody).append("\n");
                    responseLog.append("-------------------------------------------\n");

                    postLog(callback, responseLog.toString());

                    if (response.isSuccessful()) {
                        try {
                            JSONObject json = new JSONObject(rawBody);
                            String uploadedUrl = json.optString("url", "");
                            
                            // Handle NIP-94 success fallback
                            if (uploadedUrl.isEmpty() && json.has("nip94_event")) {
                                JSONArray tags = json.getJSONObject("nip94_event").getJSONArray("tags");
                                for (int i = 0; i < tags.length(); i++) {
                                    JSONArray t = tags.getJSONArray(i);
                                    if (t.length() >= 2 && "url".equals(t.getString(0))) {
                                        uploadedUrl = t.getString(1);
                                    }
                                }
                            }

                            String delUrl = json.optString("delete_url", "");
                            if (delUrl.isEmpty() && json.has("deletion_token")) {
                                delUrl = targetServer + "?token=" + json.getString("deletion_token");
                            }

                            if (!uploadedUrl.isEmpty()) {
                                postLog(callback, "[SUCCESS] MEDIA HOSTED ON NODE " + (serverIndex + 1));
                                postSuccess(callback, uploadedUrl, delUrl);
                            } else {
                                throw new Exception("Response was 200 OK but URL was missing from JSON.");
                            }
                        } catch (Exception e) {
                            postLog(callback, "!!! PARSE ERROR: " + e.getMessage() + ". Rotating...");
                            attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                        }
                    } else {
                        postLog(callback, "!!! REJECTED (" + code + "): Rotating to next node...");
                        attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                    }
                }
            });

        } catch (Exception e) {
            postLog(callback, "!!! INTERNAL PREP ERROR: " + e.getMessage());
            attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
        }
    }

    private JSONObject createNip98Event(Context context, String url, String method, String hash) throws Exception {
        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        JSONObject event = new JSONObject();
        event.put("kind", 27235);
        event.put("pubkey", db.getPublicKey());
        event.put("created_at", System.currentTimeMillis() / 1000);
        event.put("content", "");

        JSONArray tags = new JSONArray();
        tags.put(new JSONArray().put("u").put(url));
        tags.put(new JSONArray().put("method").put(method));
        if (hash != null) {
            tags.put(new JSONArray().put("payload").put(hash));
        }
        event.put("tags", tags);
        return event;
    }

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

    public void deleteMedia(Context context, String deletionUrl, MediaUploadCallback callback) {
        try {
            AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
            JSONObject n98 = createNip98Event(context, deletionUrl, "DELETE", null);
            JSONObject signed = NostrEventSigner.signEvent(db.getPrivateKey(), n98);
            String auth = "Nostr " + Base64.encodeToString(signed.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            Request req = new Request.Builder()
                    .url(deletionUrl)
                    .delete()
                    .addHeader("Authorization", auth)
                    .build();

            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { Log.e(TAG, "DELETE Network Error"); }
                @Override public void onResponse(Call call, Response response) throws IOException { 
                    Log.i(TAG, "DELETE HTTP Result: " + response.code()); 
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "DELETE Setup Error: " + e.getMessage());
        }
    }

    private void postLog(MediaUploadCallback cb, String log) { mainHandler.post(() -> cb.onStatusUpdate(log)); }
    private void postSuccess(MediaUploadCallback cb, String url, String delUrl) { mainHandler.post(() -> cb.onSuccess(url, delUrl)); }
    private void postFailure(MediaUploadCallback cb, Exception e) { mainHandler.post(() -> cb.onFailure(e)); }
}