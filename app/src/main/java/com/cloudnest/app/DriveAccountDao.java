package com.cloudnest.app;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object (DAO) for DriveAccountEntity.
 * Defines all the database query methods needed for managing multiple
 * Google Drive accounts for the "Drive Cycling" feature.
 */
@Dao
public interface DriveAccountDao {

    /**
     * Inserts a new Google Drive account into the database.
     * If an account with the same email already exists, it will be replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DriveAccountEntity account);

    /**
     * Updates an existing account's details (e.g., storage quota, active status).
     */
    @Update
    void update(DriveAccountEntity account);

    /**
     * Deletes a specific account from the database.
     */
    @Delete
    void delete(DriveAccountEntity account);

    /**
     * Retrieves all connected Google Drive accounts from the database.
     * Uses LiveData to allow the UI to observe changes in real-time.
     */
    @Query("SELECT * FROM drive_accounts ORDER BY email ASC")
    LiveData<List<DriveAccountEntity>> getAllAccounts();

    /**
     * Finds a specific account by its email address.
     */
    @Query("SELECT * FROM drive_accounts WHERE email = :email LIMIT 1")
    DriveAccountEntity getAccountByEmail(String email);

    /**
     * Gets the total number of accounts connected to the app.
     */
    @Query("SELECT COUNT(*) FROM drive_accounts")
    int getAccountCount();
    
    /**
     * Sets a specific account as the active destination for uploads.
     * Updated SQL column name to 'is_active' to match DriveAccountEntity.
     */
    @Query("UPDATE drive_accounts SET is_active = :isActive WHERE email = :email")
    void setActive(String email, boolean isActive);

    /**
     * Sets ALL accounts to inactive.
     * Updated SQL column name to 'is_active' to match DriveAccountEntity.
     */
    @Query("UPDATE drive_accounts SET is_active = 0")
    void resetAllActive();

    /**
     * Gets the currently active account for uploads.
     * Updated SQL column names to 'is_active' and 'is_full' to match DriveAccountEntity.
     */
    @Query("SELECT * FROM drive_accounts WHERE is_active = 1 AND is_full = 0 LIMIT 1")
    DriveAccountEntity getActiveAccount();
}