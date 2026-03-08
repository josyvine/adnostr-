package com.cloudnest.app;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object (DAO) for PresetFolderEntity.
 * Defines the database queries required to manage the list of folders
 * configured for automatic background backup.
 */
@Dao
public interface PresetFolderDao {

    /**
     * Inserts a new folder into the auto-backup configuration.
     * If a folder with the same local path already exists, the record will be replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PresetFolderEntity presetFolder);

    /**
     * Deletes a folder from the auto-backup configuration.
     * This stops the folder from being synced in the future.
     */
    @Delete
    void delete(PresetFolderEntity presetFolder);

    /**
     * Deletes a preset folder using its unique ID.
     */
    @Query("DELETE FROM preset_folders WHERE id = :id")
    void deleteById(long id);

    /**
     * Retrieves all configured preset folders from the database.
     * Updated SQL query to order by 'folder_name' to match PresetFolderEntity.
     */
    @Query("SELECT * FROM preset_folders ORDER BY folder_name ASC")
    LiveData<List<PresetFolderEntity>> getAllPresets();

    /**
     * Updates the last synchronization timestamp for a specific preset folder.
     * Updated SQL query to use 'last_sync_time' to match PresetFolderEntity.
     */
    @Query("UPDATE preset_folders SET last_sync_time = :timestamp WHERE id = :id")
    void updateSyncTime(long id, long timestamp);

    /**
     * Checks if a specific local folder path is already configured for auto-backup.
     * Updated SQL query to use 'local_path' to match PresetFolderEntity.
     * @param path The absolute local path of the folder.
     * @return The PresetFolderEntity if it exists, otherwise null.
     */
    @Query("SELECT * FROM preset_folders WHERE local_path = :path LIMIT 1")
    PresetFolderEntity findByPath(String path);
}