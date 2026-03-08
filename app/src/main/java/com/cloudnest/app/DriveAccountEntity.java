package com.cloudnest.app;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Entity (Table) for storing multiple Google Drive accounts.
 * This is the core component for the Multi-Drive Cycling feature.
 * The primary key is the user's email address to prevent duplicates.
 */
@Entity(tableName = "drive_accounts")
public class DriveAccountEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "email")
    public String email = "";

    @ColumnInfo(name = "display_name")
    public String displayName;

    @ColumnInfo(name = "is_active")
    public boolean isActive;

    @ColumnInfo(name = "is_full")
    public boolean isFull;

    @ColumnInfo(name = "total_space")
    public long totalSpace; // In bytes

    @ColumnInfo(name = "used_space")
    public long usedSpace; // In bytes

    // You can add access token/refresh token fields here if you need to store them
    // It is highly recommended to encrypt them before saving to the database.
    // @ColumnInfo(name = "access_token")
    // public String accessToken;
}