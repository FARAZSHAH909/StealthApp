package com.notrack.stealth.watchdog;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.notrack.stealth.BootReceiver;
import com.notrack.stealth.StealthService;

/**
 * SECONDARY ISOLATED PROCESS WATCHDOG SERVICE
 * Resides in process ":watchdog".
 * Survives when the main UI or listener process is garbage collected or killed.
 */
public class WatchdogService extends Service {

    private static final String TAG = "WatchdogService";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            performPulseCheck();
            handler.postDelayed(this, 30 * 60 * 1000L); // Every 30 minutes
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WatchdogService started in secondary process :watchdog");
        WatchdogReceiver.scheduleNextPulse(this);
        handler.post(healthCheckRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        performPulseCheck();
        return START_STICKY;
    }

    private void performPulseCheck() {
        try {
            Log.d(TAG, "Isolated watchdog performing pulse check...");
            // 1. Rebind notification listener via system
            StealthService.rebindService(this);

            // 2. Ping main process BootReceiver to execute sync and scan in main process
            Intent pingIntent = new Intent(this, BootReceiver.class);
            pingIntent.setAction(WatchdogReceiver.ACTION_WATCHDOG_PULSE);
            sendBroadcast(pingIntent);
        } catch (Exception e) {
            Log.w(TAG, "Watchdog pulse check notice: " + e.getMessage());
        }
    }

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, WatchdogService.class);
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start WatchdogService directly: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(healthCheckRunnable);
        WatchdogReceiver.scheduleNextPulse(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
