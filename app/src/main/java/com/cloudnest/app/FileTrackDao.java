package com.cloudnest.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/**
 * Data Access Object (DAO) for FileTrackEntity.
 * Manages the database operations for the global File Number Tracking System,
 * which ensures upload sequence numbers are continuous across multiple Drive accounts.
 */
@Dao
public interface FileTrackDao {

    /**
     * Inserts a record of an uploaded file into the tracking database.
     * This logs the file's local path, its assigned sequence number, and which Drive account it was saved to.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(FileTrackEntity fileTrack);

    /**
     * Retrieves the highest (latest) sequence number from the database.
     * This is the most critical query for ensuring the number sequence continues
     * seamlessly when switching between drives.
     * @return The maximum sequence number, or 0 if the table is empty.
     */
    @Query("SELECT MAX(sequenceNumber) FROM file_tracking")
    int getLatestSequenceNumber();

    /**
     * Checks if a file from a specific local path has already been uploaded and tracked.
     * This can be used to prevent duplicate uploads in the Auto-Backup feature.
     * @param localPath The absolute path of the local file.
     * @return The FileTrackEntity if found, otherwise null.
     */
    @Query("SELECT * FROM file_tracking WHERE localPath = :localPath LIMIT 1")
    FileTrackEntity findByLocalPath(String localPath);

    /**
     * Deletes all records from the file tracking table.
     * (Useful for a full reset if needed).
     */
    @Query("DELETE FROM file_tracking")
    void clearAllTrackingData();
}