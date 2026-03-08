package com.cloudnest.app;

import android.content.Context;
import android.util.Log;

/**
 * Core Business Logic for the File Number Tracking System.
 * Ensures that file sequence numbers (e.g., photo51.jpg) remain continuous
 * across multiple Google Drive accounts.
 */
public class SequenceTrackerHelper {

    private static final String TAG = "SequenceTrackerHelper";
    private final CloudNestDatabase db;

    public SequenceTrackerHelper(Context context) {
        this.db = CloudNestDatabase.getInstance(context);
    }

    /**
     * Generates the next sequential number for an upload.
     * This method is thread-safe and queries the database for the current maximum.
     * 
     * @return The next available sequence number.
     */
    public synchronized int getNextSequenceNumber() {
        int latestNumber = db.fileTrackDao().getLatestSequenceNumber();
        return latestNumber + 1;
    }

    /**
     * Records an uploaded file in the tracking database.
     * 
     * @param localPath The absolute path of the file on the device.
     * @param sequenceNumber The assigned number for this file.
     * @param driveAccountId The email of the Google account used for this upload.
     * @param driveFileId The unique ID of the file in Google Drive.
     */
    public void trackFileUpload(String localPath, int sequenceNumber, String driveAccountId, String driveFileId) {
        FileTrackEntity entity = new FileTrackEntity();
        entity.localPath = localPath;
        entity.sequenceNumber = sequenceNumber;
        entity.driveAccountId = driveAccountId;
        entity.driveFileId = driveFileId;
        entity.uploadTimestamp = System.currentTimeMillis();

        db.fileTrackDao().insert(entity);
        
        Log.d(TAG, "Tracked file: " + localPath + " as #" + sequenceNumber + " on account: " + driveAccountId);
    }

    /**
     * Generates a standardized file name based on the sequence number.
     * Example: photo51.jpg
     * 
     * @param extension The file extension (e.g., .jpg, .pdf).
     * @return A formatted name string.
     */
    public String generateFileName(String extension) {
        int nextNum = getNextSequenceNumber();
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