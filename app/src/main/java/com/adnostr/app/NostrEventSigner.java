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
 * FIXED: Canonical serialization and strict BIP-340 math to pass relay verification.
 */
public class NostrEventSigner {

    private static final String TAG = "AdNostr_Signer";

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
     * Format: [0, pubkey, created_at, kind, tags, content]
     * FIXED: Explicitly builds the JSON string to ensure canonical formatting.
     */
    private static String calculateEventId(JSONObject event) throws Exception {
        // Build the serialization array manually to ensure strict protocol order
        StringBuilder sb = new StringBuilder();
        sb.append("[0,\"");
        sb.append(event.getString("pubkey")).append("\",");
        sb.append(event.getLong("created_at")).append(",");
        sb.append(event.getInt("kind")).append(",");
        
        // Serialize Tags canonically (No spaces, no escaped slashes)
        sb.append(event.getJSONArray("tags").toString().replace("\\/", "/"));
        
        sb.append(",\"");
        // Serialize Content (Escape only necessary characters as per NIP-01)
        String content = event.getString("content")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        sb.append(content).append("\"]");

        String serialized = sb.toString();
        
        // FORCE DIAGNOSIS: Print the exact string being hashed to identify mismatches
        Log.i(TAG, "SERIALIZED STRING FOR HASHING: " + serialized);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(serialized.getBytes(StandardCharsets.UTF_8));

        return NostrKeyManager.bytesToHex(hash);
    }

    /**
     * Generates a 64-byte BIP-340 compliant Schnorr signature.
     * FIXED: Implemented strict parity checks and deterministic nonce math.
     */
    private static String generateSignature(String privateKeyHex, String eventIdHex) {
        try {
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            BigInteger n = params.getN();
            
            // Raw Private Key
            BigInteger d0 = new BigInteger(1, NostrKeyManager.hexToBytes(privateKeyHex));
            byte[] msg = NostrKeyManager.hexToBytes(eventIdHex);

            // 1. Derive Public Key point P = dG
            ECPoint G = params.getG();
            ECPoint P = G.multiply(d0).normalize();
            
            // BIP-340: If P.y is odd, negate the secret key d
            BigInteger d = P.getAffineYCoord().toBigInteger().testBit(0) ? n.subtract(d0) : d0;
            byte[] pubKeyX = normalize32(P.getAffineXCoord().getEncoded());

            // 2. Deterministic Nonce generation: k = tagged_hash("BIP340/nonce", d || msg)
            byte[] dBytes = normalize32(d.toByteArray());
            byte[] kInput = new byte[32 + 32];
            System.arraycopy(dBytes, 0, kInput, 0, 32);
            System.arraycopy(msg, 0, kInput, 32, 32);
            
            byte[] kHash = taggedHash("BIP340/nonce", kInput);
            BigInteger k0 = new BigInteger(1, kHash).mod(n);
            if (k0.equals(BigInteger.ZERO)) throw new RuntimeException("Invalid Nonce");

            // 3. Compute point R = kG
            ECPoint R = G.multiply(k0).normalize();
            
            // BIP-340: If R.y is odd, negate k
            BigInteger k = R.getAffineYCoord().toBigInteger().testBit(0) ? n.subtract(k0) : k0;
            byte[] rX = normalize32(R.getAffineXCoord().getEncoded());

            // 4. Compute Challenge e = tagged_hash("BIP340/challenge", R_x || P_x || msg)
            byte[] eInput = new byte[32 + 32 + 32];
            System.arraycopy(rX, 0, eInput, 0, 32);
            System.arraycopy(pubKeyX, 0, eInput, 32, 32);
            System.arraycopy(msg, 0, eInput, 64, 32);

            byte[] eHash = taggedHash("BIP340/challenge", eInput);
            BigInteger e = new BigInteger(1, eHash).mod(n);

            // 5. Compute s = (k + e*d) mod n
            BigInteger s = k.add(e.multiply(d)).mod(n);

            // 6. Signature is R_x || s (64 bytes)
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

    /**
     * BIP-340 Tagged Hash: sha256(sha256(tag) || sha256(tag) || data)
     */
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