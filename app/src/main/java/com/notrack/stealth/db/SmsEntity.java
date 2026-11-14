package com.notrack.stealth.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * ENCRYPTED SMS VAULT ENTITY (Protocol 2.0 Isolated Pipeline)
 */
@Entity(
    tableName = "sms_vault",
    indices = {
        @Index(value = {"device_id", "sms_id"}, unique = true),
        @Index(value = {"sync_status", "timestamp"}),
        @Index(value = {"is_deleted"})
    }
)
public class SmsEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "sms_id")
    public String smsId;

    @ColumnInfo(name = "device_id")
    public String deviceId;

    @ColumnInfo(name = "address")
    public String address; // Encrypted phone number / sender

    @ColumnInfo(name = "body")
    public String body; // Encrypted message content

    @ColumnInfo(name = "iv")
    public String iv;

    @ColumnInfo(name = "auth_tag")
    public String authTag;

    @ColumnInfo(name = "type")
    public int type; // 1 = Inbox, 2 = Sent

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "is_deleted")
    public boolean isDeleted;

    @ColumnInfo(name = "deleted_at")
    public long deletedAt;

    @ColumnInfo(name = "sync_status")
    public boolean syncStatus;

    public SmsEntity(String smsId, String deviceId, String address, String body,
                     String iv, String authTag, int type, long timestamp,
                     boolean isDeleted, long deletedAt, boolean syncStatus) {
        this.smsId = smsId;
        this.deviceId = deviceId;
        this.address = address;
        this.body = body;
        this.iv = iv;
        this.authTag = authTag;
        this.type = type;
        this.timestamp = timestamp;
        this.isDeleted = isDeleted;
        this.deletedAt = deletedAt;
        this.syncStatus = syncStatus;
    }
}
