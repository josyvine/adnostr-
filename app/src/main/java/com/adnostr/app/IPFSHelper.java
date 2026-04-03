package com.adnostr.app;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * Decentralized Storage Utility for AdNostr.
 * Handles uploading ad media to IPFS via HTTP Gateways.
 * Returns the CID (Content Identifier) used in Nostr kind:30001 events.
 */
public class IPFSHelper {

    private static final String TAG = "AdNostr_IPFSHelper";
    
    // Public IPFS API Endpoint (e.g., Infura, Pinata, or a custom node)
    // Note: Some public gateways require an API Key/Secret for POST requests.
    private static final String IPFS_API_URL = "https://ipfs.infura.io:5001/api/v0/add";
    
    // Recommended Public Gateway for reading images
    private static final String GATEWAY_BASE_URL = "https://cloudflare-ipfs.com/ipfs/";

    /**
     * Interface for handling asynchronous IPFS upload results.
     */
    public interface IPFSUploadCallback {
        void onSuccess(String cid, String gatewayUrl);
        void onFailure(Exception e);
    }

    /**
     * Uploads an image file to IPFS using a Multipart HTTP POST request.
     * 
     * @param imageFile The local image file to be decentralized.
     * @param callback The result listener.
     */
    public static void uploadImage(final File imageFile, final IPFSUploadCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            DataOutputStream outputStream = null;
            String boundary = "*****" + Long.toString(System.currentTimeMillis()) + "*****";
            String lineEnd = "\r\n";
            String twoHyphens = "--";

            try {
                Log.i(TAG, "Starting IPFS upload for: " + imageFile.getName());

                URL url = new URL(IPFS_API_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Connection", "Keep-Alive");
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                outputStream = new DataOutputStream(connection.getOutputStream());
                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + imageFile.getName() + "\"" + lineEnd);
                outputStream.writeBytes("Content-Type: image/jpeg" + lineEnd);
                outputStream.writeBytes(lineEnd);

                FileInputStream fileInputStream = new FileInputStream(imageFile);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.writeBytes(lineEnd);
                outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                fileInputStream.close();

                // Get Response
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // Parse JSON Response (IPFS returns "Hash" as the CID)
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String cid = jsonResponse.getString("Hash");
                    String finalUrl = generateGatewayUrl(cid);

                    Log.i(TAG, "IPFS Upload Success. CID: " + cid);
                    if (callback != null) {
                        callback.onSuccess(cid, finalUrl);
                    }
                } else {
                    throw new Exception("IPFS Gateway responded with code: " + responseCode);
                }

            } catch (Exception e) {
                Log.e(TAG, "IPFS Upload Failed: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure(e);
                }
                // AdNostrApplication global handler will catch this if it's rethrown
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    /**
     * Converts an IPFS CID into a viewable HTTPS URL.
     * 
     * @param cid The Content Identifier from the upload.
     * @return Full HTTPS gateway link.
     */
    public static String generateGatewayUrl(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        // Clean CID if it contains the ipfs:// prefix
        String cleanCid = cid.replace("ipfs://", "");
        return GATEWAY_BASE_URL + cleanCid;
    }
}