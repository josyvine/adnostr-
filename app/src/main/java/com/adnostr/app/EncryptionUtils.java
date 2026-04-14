package com.adnostr.app;

import android.util.Log;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Security Utility for AdNostr Media and Payloads.
 * Implements client-side AES-GCM encryption for NIP-96/Blossom uploads.
 * ENHANCEMENT: Added Master App-Level Payload Encryption to shield JSON content
 * from unauthorized Nostr network viewers.
 */
public class EncryptionUtils {

    private static final String TAG = "AdNostr_Encryption";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int KEY_LENGTH_BIT = 256;

    // =========================================================================
    // MASTER APP-LEVEL ENCRYPTION KEY (NEW)
    // =========================================================================
    // A 32-byte (256-bit) static AES key represented as a 64-character Hex string.
    // This turns AdNostr into a secure "Dark Pool" ad network.
    private static final String MASTER_APP_KEY_HEX = "7A24432646294A404E635266556A586E3272357538782F413F442A472D4B6150";

    /**
     * ENHANCEMENT: Master App-Level JSON Encryption
     * Encrypts the raw Nostr Ad JSON payload into an unreadable hex string
     * using the hardcoded App Signature Key.
     * 
     * @param jsonPayload The raw JSON string containing title, desc, CTA, etc.
     * @return Hexadecimal representation of the AES-GCM encrypted payload.
     */
    public static String encryptPayload(String jsonPayload) throws Exception {
        try {
            byte[] masterKey = hexToBytes(MASTER_APP_KEY_HEX);
            byte[] rawBytes = jsonPayload.getBytes("UTF-8");
            byte[] encryptedBytes = encrypt(rawBytes, masterKey);
            return bytesToHex(encryptedBytes);
        } catch (Exception e) {
            Log.e(TAG, "Payload Encryption Failed: " + e.getMessage());
            throw new Exception("Master Payload Encryption Error: " + e.getMessage());
        }
    }

    /**
     * ENHANCEMENT: Master App-Level JSON Decryption
     * Unwraps the encrypted hex string back into readable JSON using the App Signature Key.
     * If this fails, it means the payload is not from AdNostr.
     * 
     * @param encryptedHexPayload The unreadable hex string from the Nostr event content.
     * @return The decrypted raw JSON string.
     */
    public static String decryptPayload(String encryptedHexPayload) throws Exception {
        try {
            byte[] masterKey = hexToBytes(MASTER_APP_KEY_HEX);
            byte[] encryptedBytes = hexToBytes(encryptedHexPayload);
            byte[] decryptedBytes = decrypt(encryptedBytes, masterKey);
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            // We do not Log.e here to prevent log spamming when the app encounters standard Nostr traffic
            throw new Exception("Master Payload Decryption Error: Invalid AdNostr Payload");
        }
    }

    // =========================================================================
    // ORIGINAL MEDIA ENCRYPTION LOGIC (RETAINED & UNTOUCHED)
    // =========================================================================

    /**
     * Generates a random 256-bit AES key.
     * 
     * @return Raw byte array of the generated key.
     */
    public static byte[] generateAESKey() throws Exception {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_LENGTH_BIT, new SecureRandom());
            SecretKey secretKey = keyGen.generateKey();
            return secretKey.getEncoded();
        } catch (Exception e) {
            Log.e(TAG, "Key Generation Failed: " + e.getMessage());
            throw new Exception("AES Key Generation Error: " + e.getMessage());
        }
    }

    /**
     * Encrypts raw byte data using AES-GCM.
     * Prepends the 12-byte IV to the ciphertext for easy portability.
     * 
     * @param data The raw image bytes.
     * @param key  The 32-byte AES key.
     * @return Encrypted data: [12 bytes IV][Ciphertext].
     */
    public static byte[] encrypt(byte[] data, byte[] key) throws Exception {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] cipherText = cipher.doFinal(data);

            // Combine IV and Ciphertext
            byte[] encryptedData = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, encryptedData, 0, iv.length);
            System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);

            return encryptedData;
        } catch (Exception e) {
            Log.e(TAG, "Encryption Failed: " + e.getMessage());
            throw new Exception("AES Encryption Error: " + e.getMessage());
        }
    }

    /**
     * Decrypts AES-GCM data.
     * Expects the first 12 bytes to be the Initialization Vector (IV).
     * 
     * @param encryptedData The [IV][Ciphertext] bundle.
     * @param key           The 32-byte AES key extracted from Nostr Event.
     * @return The original decrypted image bytes.
     */
    public static byte[] decrypt(byte[] encryptedData, byte[] key) throws Exception {
        try {
            if (encryptedData.length < IV_LENGTH_BYTE) {
                throw new Exception("Malformed encrypted data: too short.");
            }

            // Extract IV
            byte[] iv = Arrays.copyOfRange(encryptedData, 0, IV_LENGTH_BYTE);
            // Extract Ciphertext
            byte[] cipherText = Arrays.copyOfRange(encryptedData, IV_LENGTH_BYTE, encryptedData.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
            return cipher.doFinal(cipherText);

        } catch (Exception e) {
            // Detailed error for the Technical Console
            String errorMsg = "AES Decryption Failure: " + e.getMessage();
            Log.e(TAG, errorMsg);
            throw new Exception(errorMsg);
        }
    }

    /**
     * Utility to convert bytes to Hex string for inclusion in Nostr JSON.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Utility to convert Hex string from Nostr JSON back to bytes.
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