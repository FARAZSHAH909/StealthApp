package com.notrack.stealth.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.notrack.stealth.db.AppDatabase;
import com.notrack.stealth.db.NotificationEntity;
import com.notrack.stealth.security.EncryptionUtils;

import java.util.List;

/**
 * SAFE HISTORICAL TELEMETRY MIGRATION WORKER
 *
 * Implements non-destructive re-encryption of legacy (v1.0) records:
 * 1. Reads encrypted legacy records from local SQLCipher database.
 * 2. Decrypts plaintext using preserved original Keystore key (stealth_keystore_alias).
 * 3. Re-encrypts with enrolled device key ($K_{dev}$), canonical binary AAD (is_migration = 0x01),
 *    and hardware EC P-256 digital signature.
 * 4. Pushes re-encrypted envelope to Firebase RTDB without overwriting unverified records.
 * 5. Updates local record to protocol version 2.0 upon verified sync.
 */
public class LegacyMigrationWorker extends Worker {

    private static final String TAG = "LegacyMigrationWorker";

    public LegacyMigrationWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Log.i(TAG, "Starting historical telemetry migration process...");

        try {
            AppDatabase db = AppDatabase.getInstance(context);
            List<NotificationEntity> allRecords = db.notificationDao().getAllNotifications();

            if (allRecords == null || allRecords.isEmpty()) {
                Log.i(TAG, "No local records found to migrate.");
                return Result.success();
            }

            int migratedCount = 0;
            int failedCount = 0;

            for (NotificationEntity item : allRecords) {
                // Only migrate records with protocol version 1.0 or missing AAD
                if ("1.0".equals(item.protocolVersion) || item.protocolVersion == null) {
                    try {
                        // 1. Decrypt using preserved legacy Keystore alias
                        String plaintext = EncryptionUtils.decryptWebCrypto(item.nonce, item.ciphertext);
                        if (plaintext == null || plaintext.isEmpty()) {
                            Log.w(TAG, "Could not decrypt legacy record: " + item.messageId);
                            failedCount++;
                            continue;
                        }

                        // 2. Re-encrypt with enrolled device key and migration AAD flag (0x01)
                        EncryptionUtils.SecureMessageEnvelope envelope = EncryptionUtils.encryptWithDeviceKey(
                                context,
                                plaintext,
                                item.deviceId,
                                item.messageId,
                                true // isMigration = true
                        );

                        // 3. Update Firebase RTDB with new envelope
                        boolean pushed = FirebaseHelper.sendRealtimeRestPost(item, envelope);
                        if (pushed) {
                            // 4. Update local DB record
                            item.protocolVersion = "2.0";
                            item.nonce = envelope.nonceBase64;
                            item.ciphertext = envelope.ciphertextBase64;
                            item.syncStatus = true;
                            db.notificationDao().update(item);
                            migratedCount++;
                            Log.i(TAG, "Successfully migrated legacy message ID: " + item.messageId);
                        } else {
                            Log.w(TAG, "Firebase push failed during migration for message ID: " + item.messageId);
                            failedCount++;
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "Migration failed for item " + item.messageId, ex);
                        failedCount++;
                    }
                }
            }

            Log.i(TAG, String.format("Migration finished: %d migrated successfully, %d failed.", migratedCount, failedCount));
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Fatal migration error", e);
            return Result.retry();
        }
    }
}

