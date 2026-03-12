package com.cloudnest.app;

import android.content.Context;
import android.util.Log;

/**
 * Core Business Logic for the File Number Tracking System.
 * Ensures that file sequence numbers (e.g., photo1.jpg) remain continuous
 * per preset folder.
 * UPDATED: Now generates sequence numbers based on preset_id.
 */
public class SequenceTrackerHelper {

    private static final String TAG = "SequenceTrackerHelper";
    private final CloudNestDatabase db;

    public SequenceTrackerHelper(Context context) {
        this.db = CloudNestDatabase.getInstance(context);
    }

    /**
     * Generates the next sequential number for an upload for a specific preset.
     * This ensures each folder has its own independent numbering sequence.
     * 
     * @param presetId The ID of the preset folder.
     * @return The next available sequence number.
     */
    public synchronized int getNextSequenceNumber(long presetId) {
        int latestNumber = db.fileTrackDao().getLatestSequenceNumberForPreset(presetId);
        return latestNumber + 1;
    }

    /**
     * Records an uploaded file in the tracking database.
     * 
     * @param localPath The absolute path of the file on the device.
     * @param sequenceNumber The assigned number for this file.
     * @param driveAccountId The email of the Google account used for this upload.
     * @param driveFileId The unique ID of the file in Google Drive.
     * @param presetId The parent Preset Folder ID.
     * @param fileSize The file size in bytes.
     */
    public void trackFileUpload(String localPath, int sequenceNumber, String driveAccountId, String driveFileId, long presetId, long fileSize) {
        FileTrackEntity entity = new FileTrackEntity();
        entity.localPath = localPath;
        entity.sequenceNumber = sequenceNumber;
        entity.driveAccountId = driveAccountId;
        entity.driveFileId = driveFileId;
        entity.uploadTimestamp = System.currentTimeMillis();
        entity.presetId = presetId;
        entity.fileSize = fileSize;

        db.fileTrackDao().insert(entity);
        
        Log.d(TAG, "Tracked file: " + localPath + " as #" + sequenceNumber + " on account: " + driveAccountId + " for preset: " + presetId);
    }

    /**
     * Generates a standardized file name based on the preset sequence number.
     * Example: photo51.jpg
     * 
     * @param presetId The ID of the preset folder.
     * @param extension The file extension (e.g., .jpg, .pdf).
     * @return A formatted name string.
     */
    public String generateFileName(long presetId, String extension) {
        int nextNum = getNextSequenceNumber(presetId);
        return "photo" + nextNum + extension;
    }

    /**
     * Verification check: Determines if a file is already tracked.
     * Used by the Auto-Backup worker to avoid re-uploading existing files.
     */
    public boolean isFileAlreadyTracked(String localPath) {
        return db.fileTrackDao().findByLocalPath(localPath) != null;
    }
}