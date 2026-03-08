package com.cloudnest.app;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity (Table) for tracking every single uploaded file.
 * This is the core of the continuous numbering system across multiple drives.
 * Each row represents one successfully uploaded file.
 */
@Entity(tableName = "file_tracking",
        // Create an index on the localPath for faster lookups to prevent duplicates.
        indices = {@Index(value = "local_path", unique = true)},
        // Establish a foreign key relationship with the drive_accounts table.
        foreignKeys = @ForeignKey(entity = DriveAccountEntity.class,
                                  parentColumns = "email",
                                  childColumns = "drive_account_id",
                                  onDelete = ForeignKey.CASCADE))
public class FileTrackEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    @ColumnInfo(name = "local_path")
    public String localPath;

    @ColumnInfo(name = "sequence_number")
    public int sequenceNumber;

    @NonNull
    @ColumnInfo(name = "drive_account_id") // Foreign key linking to DriveAccountEntity's email
    public String driveAccountId;

    @ColumnInfo(name = "drive_file_id") // The unique ID of the file returned by Google Drive API
    public String driveFileId;

    @ColumnInfo(name = "upload_timestamp")
    public long uploadTimestamp;
}