package com.adnostr.app;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Model for Decentralized Nostr Events.
 * Represents the standard JSON structure for all messages sent and received 
 * through decentralized relay servers.
 */
public class NostrEvent {

    @SerializedName("id")
    private String id; // 32-byte SHA256 hex of the event data

    @SerializedName("pubkey")
    private String pubkey; // 32-byte hex public key of the event creator

    @SerializedName("created_at")
    private long createdAt; // Unix timestamp in seconds

    @SerializedName("kind")
    private int kind; // 30001 for Ads, 30002 for Relay Marketplace, 10002 for Relay List

    @SerializedName("tags")
    private List<List<String>> tags; // Targeting tags e.g., [["t", "food"], ["p", "pubkey"]]

    @SerializedName("content")
    private String content; // The main payload (Ad JSON, Relay Info, or Text)

    @SerializedName("sig")
    private String sig; // 64-byte Schnorr signature

    /**
     * Default constructor for Gson deserialization.
     */
    public NostrEvent() {
        this.tags = new ArrayList<>();
    }

    /**
     * Constructor for creating a new outgoing event.
     */
    public NostrEvent(String pubkey, int kind, List<List<String>> tags, String content) {
        this.pubkey = pubkey;
        this.kind = kind;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.content = content;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    // =========================================================================
    // GETTERS AND SETTERS
    // =========================================================================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPubkey() {
        return pubkey;
    }

    public void setPubkey(String pubkey) {
        this.pubkey = pubkey;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public List<List<String>> getTags() {
        return tags;
    }

    public void setTags(List<List<String>> tags) {
        this.tags = tags;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSig() {
        return sig;
    }

    public void setSig(String sig) {
        this.sig = sig;
    }

    /**
     * Helper to find a specific tag value (e.g., finding the first "t" tag).
     */
    public String getFirstTagValue(String tagName) {
        if (tags == null) return null;
        for (List<String> tag : tags) {
            if (tag.size() > 1 && tag.get(0).equals(tagName)) {
                return tag.get(1);
            }
        }
        return null;
    }

    /**
     * Helper to collect all values for a specific tag (e.g., all "t" hashtags).
     */
    public List<String> getAllTagValues(String tagName) {
        List<String> values = new ArrayList<>();
        if (tags == null) return values;
        for (List<String> tag : tags) {
            if (tag.size() > 1 && tag.get(0).equals(tagName)) {
                values.add(tag.get(1));
            }
        }
        return values;
    }
}