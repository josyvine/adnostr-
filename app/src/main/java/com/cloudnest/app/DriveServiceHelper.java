package com.cloudnest.app;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Helper class to centralize Google Drive API operations.
 * This class specifically handles folder counting and recursive creation.
 * FIXES: Glitch 3 (Drive folder counts showing 0).
 */
public class DriveServiceHelper {

    private final Drive driveService;

    public DriveServiceHelper(Drive driveService) {
        this.driveService = driveService;
    }

    /**
     * Calculates the number of children (files and subfolders) inside a Drive folder.
     * Note: This requires a separate API call per folder.
     * @param folderId The Google Drive ID of the folder.
     * @return Total count of items.
     */
    public int getChildCount(String folderId) {
        try {
            String query = "'" + folderId + "' in parents and trashed = false";
            FileList result = driveService.files().list()
                    .setQ(query)
                    .setFields("files(id)") // We only need the IDs to count them
                    .execute();

            List<File> files = result.getFiles();
            return (files != null) ? files.size() : 0;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Searches for a folder by name within a parent directory.
     * If it doesn't exist, it creates it.
     * 
     * @param folderName Name of the folder to find/create.
     * @param parentId   Drive ID of the parent (use 'root' for top-level).
     * @return The Drive ID of the folder.
     */
    public String findOrCreateFolder(String folderName, String parentId) throws IOException {
        // 1. Search for existing folder
        String query = "mimeType = 'application/vnd.google-apps.folder' and " +
                       "name = '" + folderName + "' and '" + parentId + "' in parents and trashed = false";

        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .execute();

        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            // Found existing folder
            return result.getFiles().get(0).getId();
        } else {
            // 2. Create new folder
            File folderMeta = new File();
            folderMeta.setName(folderName);
            folderMeta.setMimeType("application/vnd.google-apps.folder");
            folderMeta.setParents(Collections.singletonList(parentId));
            
            File createdFolder = driveService.files().create(folderMeta)
                    .setFields("id")
                    .execute();
            
            return createdFolder.getId();
        }
    }

    /**
     * Returns a list of all folders inside a parent directory.
     * Useful for the Folder Selector Dialog.
     */
    public List<File> getSubFolders(String parentId) throws IOException {
        String query = "mimeType = 'application/vnd.google-apps.folder' and '" + parentId + "' in parents and trashed = false";
        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setOrderBy("name")
                .execute();
        
        return result.getFiles();
    }
}