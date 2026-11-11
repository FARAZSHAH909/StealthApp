package com.notrack.stealth.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

/**
 * HIGH-PERFORMANCE INDEXED NOTIFICATION ENTITY
 *
 * Indexed on:
 * 1. (sync_status, timestamp) -> for sub-millisecond pending sync queries without table scans.
 * 2. (message_id) -> unique index preventing accidental duplicates.
 */
@Entity(
    tableName = "notifications_log",
    indices = {
        @Index(value = {"sync_status", "timestamp"}),
        @Index(value = {"message_id"}, unique = true)
    }
)
public class NotificationEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "message_id")
    public String messageId;

    @ColumnInfo(name = "device_id")
    public String deviceId;

    @ColumnInfo(name = "app_package_name")
    public String packageName;

    @ColumnInfo(name = "encryption_algorithm")
    public String encryptionAlgorithm;

    @ColumnInfo(name = "protocol_version")
    public String protocolVersion;

    @ColumnInfo(name = "nonce")
    public String nonce; // Base64 12-byte IV

    @ColumnInfo(name = "ciphertext")
    public String ciphertext; // Base64 AES-256 GCM encrypted content

    @ColumnInfo(name = "sender_info")
    public String senderInfo; // Base64 encrypted sender info

    @ColumnInfo(name = "checksum")
    public String checksum;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "sync_status")
    public boolean syncStatus;

    @ColumnInfo(name = "auth_tag", defaultValue = "''")
    public String authTag = "";

    @ColumnInfo(name = "seq_num", defaultValue = "0")
    public long seqNum = 0L;

    @ColumnInfo(name = "key_version", defaultValue = "1")
    public int keyVersion = 1;

    @ColumnInfo(name = "device_signature", defaultValue = "''")
    public String deviceSignature = "";

    public NotificationEntity(String messageId, String deviceId, String packageName,
                              String encryptionAlgorithm, String protocolVersion, String nonce,
                              String ciphertext, String senderInfo, String checksum,
                              long timestamp, boolean syncStatus) {
        this.messageId = messageId != null ? messageId : UUID.randomUUID().toString();
        this.deviceId = deviceId;
        this.packageName = packageName;
        this.encryptionAlgorithm = encryptionAlgorithm != null ? encryptionAlgorithm : "AES-256-GCM";
        this.protocolVersion = protocolVersion != null ? protocolVersion : "2.0";
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.senderInfo = senderInfo;
        this.checksum = checksum;
        this.timestamp = timestamp;
        this.syncStatus = syncStatus;
    }
}
