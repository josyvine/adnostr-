package com.adnostr.app;

import android.util.Log;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Cryptographic Utility for Nostr Identity.
 * Responsible for generating Secp256k1 keypairs compatible with the 
 * decentralized Nostr protocol (BIP-340 Schnorr signatures).
 * UPDATED: Strictly enforced 32-byte (64 char) hex padding to prevent signature drift.
 */
public class NostrKeyManager {

    private static final String TAG = "AdNostr_KeyManager";

    static {
        // Register BouncyCastle as a security provider for Elliptic Curve operations
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Generates a new 32-byte private key and its corresponding 32-byte 
     * x-only public key.
     * 
     * @return String array: [0] = Private Key (Hex), [1] = Public Key (Hex)
     */
    public static String[] generateKeyPair() {
        try {
            // 1. Setup the Secp256k1 Curve parameters
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            ECDomainParameters domainParams = new ECDomainParameters(
                    params.getCurve(), params.getG(), params.getN(), params.getH());

            // 2. Generate a cryptographically secure 32-byte random number (The Private Key)
            SecureRandom secureRandom = new SecureRandom();
            BigInteger privateKeyInt;
            do {
                privateKeyInt = new BigInteger(256, secureRandom);
            } while (privateKeyInt.compareTo(params.getN()) >= 0 || privateKeyInt.equals(BigInteger.ZERO));

            // 3. Derive the Public Key Point (P = k * G)
            ECPoint publicKeyPoint = params.getG().multiply(privateKeyInt).normalize();

            // 4. Extract the X-coordinate (Nostr uses 32-byte x-only public keys)
            // FIXED: Ensure we extract exactly 32 bytes for the coordinate
            byte[] publicKeyBytes = publicKeyPoint.getAffineXCoord().getEncoded();
            if (publicKeyBytes.length > 32) {
                byte[] tmp = new byte[32];
                System.arraycopy(publicKeyBytes, publicKeyBytes.length - 32, tmp, 0, 32);
                publicKeyBytes = tmp;
            }

            // 5. Ensure raw 32-byte private key array (Handling sign-byte 0x00)
            byte[] rawPrivKey = privateKeyInt.toByteArray();
            if (rawPrivKey.length == 33 && rawPrivKey[0] == 0) {
                byte[] cleanPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 1, cleanPrivKey, 0, 32);
                rawPrivKey = cleanPrivKey;
            } else if (rawPrivKey.length < 32) {
                // Pad if BigInteger produces a shorter array due to leading zeros
                byte[] paddedPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 0, paddedPrivKey, 32 - rawPrivKey.length, rawPrivKey.length);
                rawPrivKey = paddedPrivKey;
            }

            // 6. Convert keys to Hexadecimal strings
            String privateKeyHex = bytesToHex(rawPrivKey);
            String publicKeyHex = bytesToHex(publicKeyBytes);

            // 7. STRICT NORMALIZATION: Ensure strings are exactly 64 characters
            // This prevents "id" and "sig" mismatches caused by leading zero truncation
            privateKeyHex = normalizeHex(privateKeyHex, 64);
            publicKeyHex = normalizeHex(publicKeyHex, 64);

            Log.i(TAG, "Identity Generation Success. PubKey: " + publicKeyHex);
            return new String[]{privateKeyHex, publicKeyHex};

        } catch (Exception e) {
            Log.e(TAG, "Cryptographic failure during key generation: " + e.getMessage());
            throw new RuntimeException("Identity Generation Failed: " + e.getMessage(), e);
        }
    }

    /**
     * Utility to ensure the Hex string is of exact required length (padding if necessary).
     */
    private static String normalizeHex(String hex, int length) {
        if (hex.length() > length) {
            return hex.substring(hex.length() - length);
        } else if (hex.length() < length) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() + hex.length() < length) {
                sb.append("0");
            }
            sb.append(hex);
            return sb.toString();
        }
        return hex;
    }

    /**
     * Converts a byte array to a Hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Converts a Hexadecimal string to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}