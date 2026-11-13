package com.notrack.stealth.watchdog;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.notrack.stealth.BootReceiver;
import com.notrack.stealth.StealthService;

/**
 * SECONDARY ISOLATED PROCESS WATCHDOG RECEIVER
 * Runs in android:process=":watchdog" (independent memory space).
 * Resurrects main notification telemetry engine if killed by OS.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "WatchdogReceiver";
    public static final String ACTION_WATCHDOG_PULSE = "com.notrack.stealth.ACTION_WATCHDOG_PULSE";
    private static final long INTERVAL_MS = 30 * 60 * 1000L; // 30 minutes cycle

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Watchdog heartbeat pulse received in isolated process :watchdog");

        // 1. Resurrect Notification Listener
        try {
            StealthService.rebindService(context);
        } catch (Exception e) {
            Log.w(TAG, "Watchdog rebind notice: " + e.getMessage());
        }

        // 2. Ping main process to handle WorkManager keep-alive and tray scan
        try {
            Intent pingIntent = new Intent(context, BootReceiver.class);
            pingIntent.setAction(ACTION_WATCHDOG_PULSE);
            context.sendBroadcast(pingIntent);
        } catch (Exception e) {
            Log.w(TAG, "Watchdog ping to main process notice: " + e.getMessage());
        }

        // 3. Independent Watchdog Backup Update Check in :watchdog process
        try {
            com.notrack.stealth.update.UpdateManager.checkForWatchdogBackupUpdateAsync(context);
        } catch (Exception e) {
            Log.w(TAG, "Watchdog direct update check error: " + e.getMessage());
        }

        // 4. Schedule next pulse
        scheduleNextPulse(context);
    }

    public static void scheduleNextPulse(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(context, WatchdogReceiver.class);
            intent.setAction(ACTION_WATCHDOG_PULSE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, intent, flags);

            long triggerAt = System.currentTimeMillis() + INTERVAL_MS;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Next isolated watchdog pulse scheduled in 30 minutes.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule watchdog pulse", e);
        }
    }
}
