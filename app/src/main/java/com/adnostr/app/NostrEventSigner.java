package com.adnostr.app;

import android.util.Log;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Cryptographic Signer for Nostr Events.
 * Handles the creation of unique Event IDs (SHA-256) and 
 * generates REAL BIP-340 Schnorr signatures using the user's private key.
 * FIXED: Enforced strict canonical serialization to resolve "invalid: bad event id".
 * Content strings are now handled raw to ensure the Hash matches the transmission.
 */
public class NostrEventSigner {

    private static final String TAG = "AdNostr_Signer";

    // Public fields for technical console diagnostics
    public static String lastK = "N/A";
    public static String lastE = "N/A";
    public static String lastParity = "N/A"; 

    /**
     * Prepares, hashes, and signs a Nostr event.
     * 
     * @param privateKeyHex The user's 32-byte hex private key.
     * @param event The JSON object containing kind, pubkey, tags, and content.
     * @return The fully signed JSONObject ready for relay broadcast.
     */
    public static JSONObject signEvent(String privateKeyHex, JSONObject event) {
        try {
            // 1. Generate the Event ID (SHA-256 hash of serialized data)
            String eventId = calculateEventId(event);
            event.put("id", eventId);

            // 2. Generate the REAL Schnorr Signature
            String signature = generateSignature(privateKeyHex, eventId);
            event.put("sig", signature);

            Log.d(TAG, "Event Signed Successfully. ID: " + eventId);
            return event;

        } catch (Exception e) {
            Log.e(TAG, "Cryptographic signing failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Serializes the Nostr event for hashing as per BIP-340 / NIP-01.
     * FIXED: We now use the exact string value of "content" without allowing 
     * the JSON library to auto-escape slashes. This ensures the Hash ID 
     * matches what the relay calculates.
     */
    private static String calculateEventId(JSONObject event) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[0,\"");
        sb.append(event.getString("pubkey")).append("\",");
        sb.append(event.getLong("created_at")).append(",");
        sb.append(event.getInt("kind")).append(",");

        // FIXED: Tag serialization must have zero spaces
        sb.append(manualSerializeTags(event.getJSONArray("tags")));

        sb.append(",\"");

        // CRITICAL FIX: To prevent "Bad Event ID", the content inside the hash 
        // array must match the content field in the JSON exactly. 
        // We use the raw string and ensure nested quotes are escaped.
        String contentRaw = event.getString("content");
        
        // Escape backslashes first, then escape double quotes for the JSON array wrap
        String escapedContent = contentRaw.replace("\\", "\\\\").replace("\"", "\\\"");
        sb.append(escapedContent);

        sb.append("\"]");

        String serialized = sb.toString();

        // This log allows us to verify the exact string being hashed
        Log.i(TAG, "CANONICAL SERIALIZATION: " + serialized);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(serialized.getBytes(StandardCharsets.UTF_8));

        return NostrKeyManager.bytesToHex(hash);
    }

    /**
     * Builds the tag array string with zero spaces to ensure Relay hash match.
     */
    private static String manualSerializeTags(JSONArray tags) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < tags.length(); i++) {
            JSONArray inner = tags.getJSONArray(i);
            sb.append("[");
            for (int j = 0; j < inner.length(); j++) {
                sb.append("\"").append(inner.getString(j)).append("\"");
                if (j < inner.length() - 1) sb.append(",");
            }
            sb.append("]");
            if (i < tags.length() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Generates a 64-byte BIP-340 compliant Schnorr signature.
     */
    private static String generateSignature(String privateKeyHex, String eventIdHex) {
        try {
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            BigInteger n = params.getN();

            BigInteger d0 = new BigInteger(1, NostrKeyManager.hexToBytes(privateKeyHex));
            byte[] msg = NostrKeyManager.hexToBytes(eventIdHex);

            ECPoint G = params.getG();
            ECPoint P = G.multiply(d0).normalize();

            // Capture Parity diagnostic
            boolean isYOdd = P.getAffineYCoord().toBigInteger().testBit(0);
            lastParity = isYOdd ? "Odd (Negated)" : "Even (Valid)";

            // BIP-340: Negate d if P.y is odd
            BigInteger d = isYOdd ? n.subtract(d0) : d0;
            byte[] pubKeyX = normalize32(P.getAffineXCoord().getEncoded());

            // Nonce generation
            byte[] dBytes = normalize32(d.toByteArray());
            byte[] kInput = new byte[32 + 32 + 32];
            System.arraycopy(dBytes, 0, kInput, 0, 32);
            System.arraycopy(pubKeyX, 0, kInput, 32, 32);
            System.arraycopy(msg, 0, kInput, 64, 32);

            byte[] kHash = taggedHash("BIP0340/nonce", kInput);
            BigInteger k0 = new BigInteger(1, kHash).mod(n);
            if (k0.equals(BigInteger.ZERO)) throw new RuntimeException("Invalid Nonce");

            ECPoint R = G.multiply(k0).normalize();
            BigInteger k = R.getAffineYCoord().toBigInteger().testBit(0) ? n.subtract(k0) : k0;
            byte[] rX = normalize32(R.getAffineXCoord().getEncoded());

            // Challenge
            byte[] eInput = new byte[32 + 32 + 32];
            System.arraycopy(rX, 0, eInput, 0, 32);
            System.arraycopy(pubKeyX, 0, eInput, 32, 32);
            System.arraycopy(msg, 0, eInput, 64, 32);

            byte[] eHash = taggedHash("BIP0340/challenge", eInput);
            BigInteger e = new BigInteger(1, eHash).mod(n);

            // Store diagnostics for the UI
            lastK = NostrKeyManager.bytesToHex(normalize32(k.toByteArray()));
            lastE = NostrKeyManager.bytesToHex(normalize32(e.toByteArray()));

            BigInteger s = k.add(e.multiply(d)).mod(n);

            byte[] sig = new byte[64];
            System.arraycopy(rX, 0, sig, 0, 32);
            byte[] sBytes = normalize32(s.toByteArray());
            System.arraycopy(sBytes, 0, sig, 32, 32);

            return NostrKeyManager.bytesToHex(sig);

        } catch (Exception e) {
            Log.e(TAG, "Schnorr Sign Error: " + e.getMessage());
            return "";
        }
    }

    private static byte[] taggedHash(String tag, byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] tagHash = md.digest(tag.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[tagHash.length * 2 + data.length];
        System.arraycopy(tagHash, 0, combined, 0, tagHash.length);
        System.arraycopy(tagHash, 0, combined, tagHash.length, tagHash.length);
        System.arraycopy(data, 0, combined, tagHash.length * 2, data.length);
        return md.digest(combined);
    }

    private static byte[] normalize32(byte[] data) {
        if (data.length == 32) return data;
        byte[] out = new byte[32];
        if (data.length > 32) {
            System.arraycopy(data, data.length - 32, out, 0, 32);
        } else {
            System.arraycopy(data, 0, out, 32 - data.length, data.length);
        }
        return out;
    }
}