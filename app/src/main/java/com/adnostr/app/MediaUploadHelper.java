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
 * UPDATED: Fixed NIP-98 Slash-Escaping bug causing 500/401 errors.
 * UPDATED: Corrected Node 4 and Node 5 Blossom Endpoints.
 * FORENSIC VERSION: Full diagnostic logging for protocol verification.
 */
public class MediaUploadHelper {

    private static final String TAG = "AdNostr_MediaForensics";

    // UPDATED: Verified active endpoints for the Blossom Protocol
    private static final String[] DEFAULT_SERVERS = {
            "https://nostr.build/api/v2/upload/files",
            "https://void.cat/upload",
            "https://pixel.buzz/upload",
            "https://nostrcheck.me/api/v2/upload",
            "https://files.sovbit.host/upload"
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

    private void attemptUploadWithFallback(Context context, byte[] encryptedData, int serverIndex, MediaUploadCallback callback) {
        if (serverIndex >= DEFAULT_SERVERS.length) {
            postFailure(callback, new Exception("PROTOCOL FAILURE: All servers rejected the request. Inspect the 'RAW SERVER BODY' in the logs above."));
            return;
        }

        AdNostrDatabaseHelper db = AdNostrDatabaseHelper.getInstance(context);
        String customServer = db.getCustomMediaServer();
        String targetServer = (serverIndex == 0 && customServer != null && !customServer.isEmpty()) 
                ? customServer : DEFAULT_SERVERS[serverIndex];

        StringBuilder forensicLog = new StringBuilder();
        forensicLog.append("=== INITIATING UPLOAD Node ").append(serverIndex + 1).append(" ===\n");
        forensicLog.append("URL: ").append(targetServer).append("\n");

        try {
            // 1. Calculate Payload Hash for NIP-98
            String payloadHash = calculateSha256(encryptedData);
            forensicLog.append("SHA256: ").append(payloadHash).append("\n");

            // 2. Generate NIP-98 Event
            JSONObject n98Event = createNip98Event(context, targetServer, "POST", payloadHash);
            
            // 3. Sign the Event
            JSONObject signedN98 = NostrEventSigner.signEvent(db.getPrivateKey(), n98Event);
            if (signedN98 == null) throw new Exception("Nostr Signer returned NULL.");

            // 4. CRITICAL FIX: ToCanonicalString
            // Standard JSONObject.toString() escapes slashes (e.g. https:\/\/nostr.build).
            // This causes signature mismatches on the server. We must strip the backslashes.
            String canonicalJson = signedN98.toString().replace("\\/", "/");
            forensicLog.append("CANONICAL NIP-98 AUTH (Slashes Unescaped):\n").append(canonicalJson).append("\n");

            String base64Auth = Base64.encodeToString(canonicalJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String authHeader = "Nostr " + base64Auth;

            // 5. Prepare Multipart with strict MIME masquerade
            RequestBody fileBody = RequestBody.create(encryptedData, MediaType.parse("image/jpeg"));
            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "image.jpg", fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(targetServer)
                    .post(requestBody)
                    .addHeader("Authorization", authHeader)
                    .addHeader("User-Agent", "AdNostr-Forensic-Client/1.2")
                    .build();

            forensicLog.append("HTTP Authorization sent.\n");
            postLog(callback, forensicLog.toString());

            // 6. Execute Async Request
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postLog(callback, "!!! Node " + (serverIndex + 1) + " Network Failure: " + e.getMessage());
                    attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String rawBody = response.body() != null ? response.body().string() : "EMPTY_BODY";
                    int code = response.code();

                    StringBuilder responseLog = new StringBuilder();
                    responseLog.append("--- Node ").append(serverIndex + 1).append(" Server Response ---\n");
                    responseLog.append("HTTP CODE: ").append(code).append("\n");
                    responseLog.append("RAW SERVER BODY:\n").append(rawBody).append("\n");
                    responseLog.append("-------------------------------------------\n");

                    postLog(callback, responseLog.toString());

                    if (response.isSuccessful()) {
                        try {
                            JSONObject json = new JSONObject(rawBody);
                            String uploadedUrl = json.optString("url", "");
                            
                            // NIP-94 success handler
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
                                postLog(callback, "[SUCCESS] Media successfully hosted on Node " + (serverIndex + 1));
                                postSuccess(callback, uploadedUrl, delUrl);
                            } else {
                                throw new Exception("Response was 200 but no URL found.");
                            }
                        } catch (Exception e) {
                            postLog(callback, "!!! Node " + (serverIndex + 1) + " Parse Error. Rotating...");
                            attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                        }
                    } else {
                        postLog(callback, "!!! Node " + (serverIndex + 1) + " Rejected with " + code + ". Rotating...");
                        attemptUploadWithFallback(context, encryptedData, serverIndex + 1, callback);
                    }
                }
            });

        } catch (Exception e) {
            postLog(callback, "!!! INTERNAL Node " + (serverIndex + 1) + " PREP ERROR: " + e.getMessage());
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
            
            // Apply Canonical fix to Deletion as well
            String canonicalJson = signed.toString().replace("\\/", "/");
            String auth = "Nostr " + Base64.encodeToString(canonicalJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            Request req = new Request.Builder()
                    .url(deletionUrl)
                    .delete()
                    .addHeader("Authorization", auth)
                    .build();

            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { Log.e(TAG, "DELETE Network Error"); }
                @Override public void onResponse(Call call, Response response) { Log.i(TAG, "DELETE Result: " + response.code()); }
            });
        } catch (Exception e) {
            Log.e(TAG, "DELETE Setup Error: " + e.getMessage());
        }
    }

    private void postLog(MediaUploadCallback cb, String log) { mainHandler.post(() -> cb.onStatusUpdate(log)); }
    private void postSuccess(MediaUploadCallback cb, String url, String delUrl) { mainHandler.post(() -> cb.onSuccess(url, delUrl)); }
    private void postFailure(MediaUploadCallback cb, Exception e) { mainHandler.post(() -> cb.onFailure(e)); }
}