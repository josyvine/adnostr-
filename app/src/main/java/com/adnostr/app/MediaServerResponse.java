package com.adnostr.app;

/**
 * Data Model for Encrypted Media Results.
 * This class captures the metadata returned by a Blossom/NIP-96 server 
 * after a successful encrypted upload.
 */
public class MediaServerResponse {

    private String url;
    private String deletionUrl;
    private String aesKeyHex;

    /**
     * Default constructor for the Media Response.
     * 
     * @param url          The public HTTPS link where the encrypted file is hosted.
     * @param deletionUrl  The unique endpoint or token used to delete the file.
     * @param aesKeyHex    The hex string of the AES key used to encrypt this specific file.
     */
    public MediaServerResponse(String url, String deletionUrl, String aesKeyHex) {
        this.url = url;
        this.deletionUrl = deletionUrl;
        this.aesKeyHex = aesKeyHex;
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    /**
     * @return The public URL of the media.
     */
    public String getUrl() {
        return url;
    }

    /**
     * @return The deletion URL provided by the server.
     */
    public String getDeletionUrl() {
        return deletionUrl;
    }

    /**
     * @return The AES key in Hex format.
     */
    public String getAesKeyHex() {
        return aesKeyHex;
    }

    // =========================================================================
    // SETTERS
    // =========================================================================

    public void setUrl(String url) {
        this.url = url;
    }

    public void setDeletionUrl(String deletionUrl) {
        this.deletionUrl = deletionUrl;
    }

    public void setAesKeyHex(String aesKeyHex) {
        this.aesKeyHex = aesKeyHex;
    }

    /**
     * Diagnostic string for the Technical Console logs.
     */
    @Override
    public String toString() {
        return "MediaServerResponse{" +
                "url='" + url + '\'' +
                ", hasDeletionUrl=" + (deletionUrl != null && !deletionUrl.isEmpty()) +
                ", keyLength=" + (aesKeyHex != null ? aesKeyHex.length() : 0) +
                '}';
    }
}