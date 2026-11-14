package com.notrack.stealth.update;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * RECEIVER FOR SYSTEM NOTIFICATION UPDATE ACTIONS
 *
 * Handles 1-tap update triggers directly from the Android status bar / notification shade.
 * Starts UpdateDownloadService as a foreground service without needing the app UI to be open.
 */
public class UpdateActionReceiver extends BroadcastReceiver {

    private static final String TAG = "UpdateActionReceiver";
    public static final String ACTION_TRIGGER_DOWNLOAD = "com.notrack.stealth.action.TRIGGER_DOWNLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_TRIGGER_DOWNLOAD.equals(intent.getAction())) return;

        String apkUrl = intent.getStringExtra(UpdateDownloadService.EXTRA_APK_URL);
        String versionName = intent.getStringExtra(UpdateDownloadService.EXTRA_VERSION_NAME);
        int versionCode = intent.getIntExtra(UpdateDownloadService.EXTRA_VERSION_CODE, 1);
        String title = intent.getStringExtra(UpdateDownloadService.EXTRA_RELEASE_TITLE);
        String sha256 = intent.getStringExtra(UpdateDownloadService.EXTRA_SHA256_HASH);

        Log.i(TAG, "Notification action tapped: Initiating background download for v" + versionName);

        // Cancel the 'Update Available' banner notification
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(UpdateManager.NOTIFICATION_ID);
            }
        } catch (Exception ignored) {}

        try {
            Toast.makeText(context, "🚀 Starting background download for v" + versionName + "...", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}

        // Launch high-speed resumable download foreground service with SHA-256 integrity verification
        UpdateDownloadService.startDownload(context, apkUrl, versionName, versionCode, title, sha256);
    }
}
