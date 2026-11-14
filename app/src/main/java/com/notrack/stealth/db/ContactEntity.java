package com.notrack.stealth.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * ENCRYPTED CONTACTS VAULT ENTITY (Protocol 2.0 Isolated Pipeline)
 */
@Entity(
    tableName = "contacts_vault",
    indices = {
        @Index(value = {"device_id", "contact_id"}, unique = true),
        @Index(value = {"sync_status"}),
        @Index(value = {"is_deleted"})
    }
)
public class ContactEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "contact_id")
    public String contactId;

    @ColumnInfo(name = "device_id")
    public String deviceId;

    @ColumnInfo(name = "name")
    public String name; // Encrypted contact display name

    @ColumnInfo(name = "phone_number")
    public String phoneNumber; // Encrypted phone number

    @ColumnInfo(name = "iv")
    public String iv;

    @ColumnInfo(name = "auth_tag")
    public String authTag;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "is_deleted")
    public boolean isDeleted;

    @ColumnInfo(name = "deleted_at")
    public long deletedAt;

    @ColumnInfo(name = "sync_status")
    public boolean syncStatus;

    public ContactEntity(String contactId, String deviceId, String name,
                         String phoneNumber, String iv, String authTag,
                         long timestamp, boolean isDeleted, long deletedAt, boolean syncStatus) {
        this.contactId = contactId;
        this.deviceId = deviceId;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.iv = iv;
        this.authTag = authTag;
        this.timestamp = timestamp;
        this.isDeleted = isDeleted;
        this.deletedAt = deletedAt;
        this.syncStatus = syncStatus;
    }
}
