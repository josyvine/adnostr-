package com.cloudnest.app;

/**
 * Universal Data Model for Files and Folders.
 * This object is used to feed the FileBrowserAdapter for both local and cloud items.
 * UPDATED: Added thumbnail support and ensured child count visibility.
 */
public class FileItemModel {

    private String driveId;      // Only used for Google Drive items
    private String name;
    private String path;         // Only used for Local items (Absolute Path)
    private boolean isDirectory;
    private long lastModified;
    private long size;           // In bytes
    private int childCount;      // Used for folders to show "Folder (120)"
    private String mimeType;     // Used for Drive items
    private String webLink;      // Used for Drive items (for sharing)
    private String thumbnailUrl; // NEW: For Google Drive thumbnails

    // Constructor for Local Files (Phone/SD)
    public FileItemModel(String name, String path, boolean isDirectory, long lastModified, long size, int childCount) {
        this.name = name;
        this.path = path;
        this.isDirectory = isDirectory;
        this.lastModified = lastModified;
        this.size = size;
        this.childCount = childCount;
    }

    // NEW Constructor to fix the build error in DriveBrowserFragment
    public FileItemModel(String driveId, String name, boolean isDirectory, long lastModified, long size, String mimeType, String webLink) {
        this.driveId = driveId;
        this.name = name;
        this.isDirectory = isDirectory;
        this.lastModified = lastModified;
        this.size = size;
        this.mimeType = mimeType;
        this.webLink = webLink;
    }

    // Constructor for Google Drive Files
    public FileItemModel(String driveId, String name, boolean isDirectory, long lastModified, long size, String mimeType, String webLink, String thumbnailUrl, int childCount) {
        this.driveId = driveId;
        this.name = name;
        this.isDirectory = isDirectory;
        this.lastModified = lastModified;
        this.size = size;
        this.mimeType = mimeType;
        this.webLink = webLink;
        this.thumbnailUrl = thumbnailUrl;
        this.childCount = childCount;
    }

    // Getters
    public String getDriveId() { return driveId; }
    public String getName() { return name; }
    public String getPath() { return path; }
    public boolean isDirectory() { return isDirectory; }
    public long getLastModified() { return lastModified; }
    public long getSize() { return size; }
    public int getChildCount() { return childCount; }
    public String getMimeType() { return mimeType; }
    public String getWebLink() { return webLink; }
    public String getThumbnailUrl() { return thumbnailUrl; }

    // Setters
    public void setDriveId(String driveId) { this.driveId = driveId; }
    public void setName(String name) { this.name = name; }
    public void setPath(String path) { this.path = path; }
    public void setDirectory(boolean directory) { isDirectory = directory; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public void setSize(long size) { this.size = size; }
    public void setChildCount(int childCount) { this.childCount = childCount; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public void setWebLink(String webLink) { this.webLink = webLink; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
}