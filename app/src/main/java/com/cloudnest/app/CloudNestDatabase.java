package com.cloudnest.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Main Room Database for CloudNest.
 * This class ties together all the Entities (tables) and DAOs (queries).
 * It follows a singleton pattern to ensure only one instance of the database
 * is ever created in the application.
 *
 * Entities:
 * 1. DriveAccountEntity: Stores multiple Google Drive accounts for the cycling feature.
 * 2. FileTrackEntity: Manages the global sequential numbering of uploaded files.
 * 3. PresetFolderEntity: Stores configuration for auto-backup folders.
 */
@Database(entities = {DriveAccountEntity.class, FileTrackEntity.class, PresetFolderEntity.class}, version = 2, exportSchema = false)
public abstract class CloudNestDatabase extends RoomDatabase {

    // Define the DAOs that the database will provide
    public abstract DriveAccountDao driveAccountDao();
    public abstract FileTrackDao fileTrackDao();
    public abstract PresetFolderDao presetFolderDao();

    private static volatile CloudNestDatabase INSTANCE;

    // Singleton pattern to prevent multiple instances of the database
    public static CloudNestDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (CloudNestDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            CloudNestDatabase.class, "cloudnest_database")
                            // Fallback to destructive migration for simplicity.
                            // In a production app, you would implement proper migration paths.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}