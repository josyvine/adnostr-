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
            byte[] publicKeyBytes = publicKeyPoint.getAffineXCoord().getEncoded();

            // 5. Convert keys to Hexadecimal strings for storage and relay transmission
            String privateKeyHex = bytesToHex(privateKeyInt.toByteArray());
            String publicKeyHex = bytesToHex(publicKeyBytes);

            // Ensure the private key is exactly 64 characters (32 bytes)
            // Sometimes BigInteger.toByteArray() adds a sign byte or is shorter
            privateKeyHex = normalizeHex(privateKeyHex, 64);

            Log.i(TAG, "Identity Generation Success.");
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
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}