package com.cloudnest.app;

import android.content.Context;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for Google Drive API operations.
 * Centralizes service initialization, folder search/creation, and quota checks.
 */
public class DriveApiHelper {

    /**
     * Builds and returns a fully authenticated Google Drive Service.
     */
    public static Drive getDriveService(Context context, GoogleSignInAccount account) {
        GoogleAccountCredential credential = GoogleAuthHelper.getCredential(context, account);
        return new Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                new GsonFactory(),
                credential)
                .setApplicationName(context.getString(R.string.app_name))
                .build();
    }

    /**
     * Checks if a specific folder exists by name within a parent directory.
     * @return The Drive ID if found, or null.
     */
    public static String findFolderId(Drive driveService, String folderName, String parentId) throws IOException {
        String query = "name = '" + folderName + "' and mimeType = 'application/vnd.google-apps.folder' and '" + parentId + "' in parents and trashed = false";
        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .execute();

        List<File> files = result.getFiles();
        return (files != null && !files.isEmpty()) ? files.get(0).getId() : null;
    }

    /**
     * Creates a new folder in Google Drive.
     * @return The ID of the newly created folder.
     */
    public static String createFolder(Drive driveService, String folderName, String parentId) throws IOException {
        File folderMeta = new File();
        folderMeta.setName(folderName);
        folderMeta.setMimeType("application/vnd.google-apps.folder");
        folderMeta.setParents(Collections.singletonList(parentId));

        File folder = driveService.files().create(folderMeta).setFields("id").execute();
        return folder.getId();
    }

    /**
     * Retrieves the storage quota status for the connected account.
     * Used by the Drive Cycling logic to check if a Drive is full.
     */
    public static About.StorageQuota getStorageQuota(Drive driveService) throws IOException {
        About about = driveService.about().get().setFields("storageQuota").execute();
        return about.getStorageQuota();
    }

    /**
     * Helper to determine if the drive is nearly full.
     * Returns true if used space is > 95% of total limit.
     */
    public static boolean isDriveFull(About.StorageQuota quota) {
        long used = quota.getUsage();
        long limit = quota.getLimit();
        if (limit == 0) return false;
        return ((double) used / limit) > 0.95;
    }
}