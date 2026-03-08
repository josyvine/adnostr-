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
     * Updated SQL query to use 'sequence_number' to match FileTrackEntity.
     * @return The maximum sequence number, or 0 if the table is empty.
     */
    @Query("SELECT MAX(sequence_number) FROM file_tracking")
    int getLatestSequenceNumber();

    /**
     * Checks if a file from a specific local path has already been uploaded and tracked.
     * Updated SQL query to use 'local_path' to match FileTrackEntity.
     * @param localPath The absolute path of the local file.
     * @return The FileTrackEntity if found, otherwise null.
     */
    @Query("SELECT * FROM file_tracking WHERE local_path = :localPath LIMIT 1")
    FileTrackEntity findByLocalPath(String localPath);

    /**
     * Deletes all records from the file tracking table.
     */
    @Query("DELETE FROM file_tracking")
    void clearAllTrackingData();
}