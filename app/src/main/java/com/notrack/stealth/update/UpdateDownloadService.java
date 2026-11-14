package com.notrack.stealth.update;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.notrack.stealth.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HIGH-SPEED RESUMABLE BACKGROUND UPDATE DOWNLOAD SERVICE
 *
 * Key Capabilities:
 *  1. Foreground Service Architecture: Survives when the user swipes away or kills MainActivity.
 *  2. Resumable Downloads: Uses HTTP 'Range: bytes=X-' headers to resume interrupted or corrupt downloads.
 *  3. Maximum Throughput: 128 KB buffered I/O streams with redirect following.
 *  4. Native System Tray Notification: Live percentage and MB counter, switches to 1-tap installer on complete.
 */
public class UpdateDownloadService extends Service {

    private static final String TAG = "UpdateDownloadService";

    public static final String ACTION_START_DOWNLOAD = "com.notrack.stealth.action.START_DOWNLOAD";
    public static final String EXTRA_APK_URL = "extra_apk_url";
    public static final String EXTRA_VERSION_NAME = "extra_version_name";
    public static final String EXTRA_VERSION_CODE = "extra_version_code";
    public static final String EXTRA_RELEASE_TITLE = "extra_release_title";
    public static final String EXTRA_SHA256_HASH = "extra_sha256_hash";

    public static final String CHANNEL_ID = "stealth_update_download_v2";
    public static final int NOTIF_PROGRESS_ID = 8801;
    public static final int NOTIF_COMPLETE_ID = 8802;

    private static final int BUFFER_SIZE = 131072; // 128 KB High-Speed Buffer
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mProgressBuilder;
    private volatile boolean mIsDownloading = false;

    public static void startDownload(Context context, String apkUrl, String versionName, int versionCode, String title) {
        startDownload(context, apkUrl, versionName, versionCode, title, null);
    }

    public static void startDownload(Context context, String apkUrl, String versionName, int versionCode, String title, String sha256) {
        if (context == null || apkUrl == null || apkUrl.isEmpty()) return;
        if (!apkUrl.startsWith("https://")) {
            Log.e(TAG, "Security policy violation: Insecure non-HTTPS APK download URL rejected: " + apkUrl);
            return;
        }
        Intent intent = new Intent(context, UpdateDownloadService.class);
        intent.setAction(ACTION_START_DOWNLOAD);
        intent.putExtra(EXTRA_APK_URL, apkUrl);
        intent.putExtra(EXTRA_VERSION_NAME, versionName);
        intent.putExtra(EXTRA_VERSION_CODE, versionCode);
        intent.putExtra(EXTRA_RELEASE_TITLE, title);
        if (sha256 != null && !sha256.trim().isEmpty()) {
            intent.putExtra(EXTRA_SHA256_HASH, sha256.trim());
        }

        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service: " + e.getMessage(), e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_DOWNLOAD.equals(intent.getAction())) {
            String apkUrl = intent.getStringExtra(EXTRA_APK_URL);
            String versionName = intent.getStringExtra(EXTRA_VERSION_NAME);
            int versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 1);
            String title = intent.getStringExtra(EXTRA_RELEASE_TITLE);
            String sha256 = intent.getStringExtra(EXTRA_SHA256_HASH);

            if (versionName == null || versionName.isEmpty()) {
                versionName = "Latest";
            }

            // Start as Foreground Service immediately to prevent Android OS kill
            startForegroundWithInitialNotification(versionName);

            if (!mIsDownloading) {
                mIsDownloading = true;
                final String finalApkUrl = apkUrl;
                final String finalVersionName = versionName;
                final int finalVersionCode = versionCode;
                final String finalTitle = title;
                final String finalSha256 = sha256;

                DOWNLOAD_EXECUTOR.execute(() -> executeResumableDownload(finalApkUrl, finalVersionName, finalVersionCode, finalTitle, finalSha256));
            }
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "System Update Downloads",
                    NotificationManager.IMPORTANCE_LOW // Low importance prevents repetitive sound chimes on progress updates
            );
            channel.setDescription("Background download progress for security definitions and app updates");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundWithInitialNotification(String versionName) {
        mProgressBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Downloading Update v" + versionName)
                .setContentText("Connecting to high-speed cloud...")
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS_ID, mProgressBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_PROGRESS_ID, mProgressBuilder.build());
        }
    }

    /**
     * Resumable, High-Speed, Fail-Safe Download Execution Engine.
     */
    private void executeResumableDownload(String apkUrl, String versionName, int versionCode, String title, String expectedSha256) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;

        File dir = UpdateManager.getUpdateDirectory(this);
        File finalApk = new File(dir, "update.apk");
        File partFile = new File(dir, "update.apk.part");

        try {
            // URL Sanitization: Direct raw URL for GitHub
            if (apkUrl.contains("github.com") && apkUrl.contains("/blob/")) {
                apkUrl = apkUrl.replace("/blob/", "/raw/");
            }

            long existingBytes = partFile.exists() ? partFile.length() : 0L;
            Log.i(TAG, "Starting download. Existing cached bytes: " + existingBytes);

            URL currentUrl = new URL(apkUrl);
            int redirectHops = 0;
            boolean connected = false;

            while (redirectHops < 8 && !connected) {
                conn = (HttpURLConnection) currentUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)");
                conn.setRequestProperty("Accept", "*/*");
                conn.setConnectTimeout(25000);
                conn.setReadTimeout(45000);
                conn.setInstanceFollowRedirects(true);

                // Request partial content if we already have bytes
                if (existingBytes > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                    Log.i(TAG, "Sending HTTP Range request header: bytes=" + existingBytes + "-");
                }

                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String redirectLocation = conn.getHeaderField("Location");
                    if (redirectLocation != null && !redirectLocation.isEmpty()) {
                        currentUrl = new URL(redirectLocation);
                        conn.disconnect();
                        redirectHops++;
                        Log.d(TAG, "Following redirect hop #" + redirectHops + " to " + redirectLocation);
                        continue;
                    }
                }
                connected = true;
            }

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "HTTP Response Code: " + responseCode);

            boolean isPartialContent = (responseCode == 206);
            long totalBytesExpected;
            long bytesDownloadedSoFar;

            if (isPartialContent) {
                // Server supports Range requests! Append to existing partFile
                totalBytesExpected = existingBytes + conn.getContentLengthLong();
                bytesDownloadedSoFar = existingBytes;
                out = new BufferedOutputStream(new FileOutputStream(partFile, true), BUFFER_SIZE);
                Log.i(TAG, "Server accepted Range request! Resuming from " + existingBytes + " of " + totalBytesExpected + " total bytes.");
            } else if (responseCode == 200) {
                // Server returns full file, restart clean
                totalBytesExpected = conn.getContentLengthLong();
                bytesDownloadedSoFar = 0;
                out = new BufferedOutputStream(new FileOutputStream(partFile, false), BUFFER_SIZE);
                Log.i(TAG, "Fresh download initiated. Total size: " + totalBytesExpected + " bytes.");
            } else if (responseCode == 416) {
                // Range Not Satisfiable: Check if partFile is already a complete valid APK
                if (partFile.length() > 50000) {
                    if (out != null) {
                        out.flush();
                        out.close();
                        out = null;
                    }
                    promotePartFileToApk(partFile, finalApk);
                    PackageInfo pInfo = getPackageManager().getPackageArchiveInfo(finalApk.getAbsolutePath(), 0);
                    if (pInfo != null) {
                        Log.i(TAG, "HTTP 416: Part file is already a valid complete APK. Promoting to final APK.");
                        onDownloadSuccess(finalApk, versionName);
                        return;
                    }
                }
                partFile.delete();
                throw new RuntimeException("HTTP 416 range error. Resetting cache.");
            } else {
                throw new RuntimeException("Unexpected HTTP response: " + responseCode);
            }

            in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long lastNotificationTime = 0;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                bytesDownloadedSoFar += read;

                long now = System.currentTimeMillis();
                if (now - lastNotificationTime > 400 || bytesDownloadedSoFar == totalBytesExpected) {
                    lastNotificationTime = now;
                    int percent = totalBytesExpected > 0 ? (int) ((bytesDownloadedSoFar * 100) / totalBytesExpected) : -1;
                    updateProgressNotification(versionName, percent, bytesDownloadedSoFar, totalBytesExpected);
                }
            }

            out.flush();
            out.close();
            out = null;
            if (in != null) {
                in.close();
                in = null;
            }
            if (conn != null) {
                conn.disconnect();
                conn = null;
            }

            Log.i(TAG, "Download stream finished. Bytes on disk: " + partFile.length());

            if (partFile.length() > 50000) {
                promotePartFileToApk(partFile, finalApk);

                // 1. Verify the downloaded file is a valid Android APK package
                PackageInfo pkgInfo = getPackageManager().getPackageArchiveInfo(finalApk.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
                if (pkgInfo == null) {
                    Log.e(TAG, "Downloaded file is corrupted or not a valid APK package. Deleting.");
                    finalApk.delete();
                    partFile.delete();
                    throw new RuntimeException("Downloaded package is invalid or corrupt. Please retry.");
                }

                // 2. Supply-Chain Security Check (CWE-494): Package name must match this application
                if (!getPackageName().equals(pkgInfo.packageName)) {
                    Log.e(TAG, "SECURITY VIOLATION: Package name mismatch! Expected: " + getPackageName() + ", Found: " + pkgInfo.packageName);
                    finalApk.delete();
                    partFile.delete();
                    throw new SecurityException("Security violation: Downloaded package identity mismatch.");
                }

                // 3. Version Code Verification: Must match expected server version
                if (versionCode > 0 && pkgInfo.versionCode != versionCode) {
                    Log.e(TAG, "SECURITY VIOLATION: Version code mismatch! Expected: " + versionCode + ", Found: " + pkgInfo.versionCode);
                    finalApk.delete();
                    partFile.delete();
                    throw new SecurityException("Security violation: Downloaded package version code mismatch.");
                }

                // 4. Cryptographic SHA-256 Digest Verification (CWE-353)
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                    String actualSha256 = calculateSha256(finalApk);
                    if (actualSha256 == null || !actualSha256.equalsIgnoreCase(expectedSha256.trim())) {
                        Log.e(TAG, "CRYPTOGRAPHIC ERROR: SHA-256 digest mismatch! Expected: " + expectedSha256 + ", Actual: " + actualSha256);
                        finalApk.delete();
                        partFile.delete();
                        throw new SecurityException("Cryptographic verification failed: SHA-256 digest mismatch.");
                    }
                    Log.i(TAG, "Cryptographic integrity verified: SHA-256 matches " + actualSha256);
                }

                Log.i(TAG, "Successfully validated APK: " + pkgInfo.packageName + " v" + pkgInfo.versionName + " (" + pkgInfo.versionCode + ")");
                onDownloadSuccess(finalApk, versionName);
            } else {
                partFile.delete();
                throw new RuntimeException("Downloaded file too small or incomplete: " + partFile.length() + " bytes.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Download interrupted or error: " + e.getMessage(), e);
            onDownloadFailed(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Network error");
        } finally {
            mIsDownloading = false;
            try {
                if (out != null) out.close();
                if (in != null) in.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    private void promotePartFileToApk(File partFile, File finalApk) {
        if (finalApk.exists()) {
            finalApk.delete();
        }
        boolean renamed = partFile.renameTo(finalApk);
        if (!renamed) {
            try (InputStream inStream = new BufferedInputStream(new java.io.FileInputStream(partFile));
                 OutputStream outStream = new BufferedOutputStream(new FileOutputStream(finalApk))) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = inStream.read(buf)) > 0) {
                    outStream.write(buf, 0, len);
                }
                outStream.flush();
                partFile.delete();
            } catch (Exception e) {
                Log.w(TAG, "Copy fallback on promote error: " + e.getMessage());
            }
        }
        finalApk.setReadable(true, false);
        Log.i(TAG, "Promoted APK to final destination: " + finalApk.getAbsolutePath() + " (" + finalApk.length() + " bytes)");
    }

    private void updateProgressNotification(String versionName, int percent, long downloadedBytes, long totalBytes) {
        if (mNotificationManager == null || mProgressBuilder == null) return;

        double downloadedMb = downloadedBytes / (1024.0 * 1024.0);
        double totalMb = totalBytes / (1024.0 * 1024.0);

        String progressText;
        if (totalBytes > 0 && percent >= 0) {
            progressText = String.format(Locale.US, "%d%% (%.1f MB / %.1f MB) • High Speed", percent, downloadedMb, totalMb);
            mProgressBuilder.setProgress(100, percent, false);
        } else {
            progressText = String.format(Locale.US, "%.1f MB downloaded • High Speed", downloadedMb);
            mProgressBuilder.setProgress(100, 0, true);
        }

        mProgressBuilder.setContentText(progressText);
        mNotificationManager.notify(NOTIF_PROGRESS_ID, mProgressBuilder.build());
    }

    private void onDownloadSuccess(final File apkFile, final String versionName) {
        Log.i(TAG, "Download successfully completed for v" + versionName + "!");

        // 1. Remove foreground progress notification
        stopForeground(true);

        // 2. Build 1-Tap Package Install Intent
        Intent installIntent = UpdateManager.getInstallIntent(this, apkFile);
        PendingIntent pendingInstallIntent = null;
        if (installIntent != null) {
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            pendingInstallIntent = PendingIntent.getActivity(this, 9005, installIntent, piFlags);
        }

        // 3. Post Clean High-Priority "Ready to Install" Notification in System Tray
        NotificationCompat.Builder completeBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("✅ System Update Ready: v" + versionName)
                .setContentText("Download complete (100%). Tap here to install update now.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Download complete (100%). Tap here to install update now."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true);

        if (pendingInstallIntent != null) {
            completeBuilder.setContentIntent(pendingInstallIntent);
        }

        if (mNotificationManager != null) {
            mNotificationManager.notify(NOTIF_COMPLETE_ID, completeBuilder.build());
        }

        // 4. If user is currently looking at the app, immediately trigger installer dialog
        MAIN_HANDLER.post(() -> {
            Toast.makeText(UpdateDownloadService.this, "Update v" + versionName + " downloaded! Launching installer...", Toast.LENGTH_LONG).show();
            if (UpdateManager.isActivityInForeground()) {
                UpdateManager.installApk(UpdateDownloadService.this, apkFile);
            }
        });

        stopSelf();
    }

    private void onDownloadFailed(String errorMsg) {
        stopForeground(true);

        NotificationCompat.Builder failedBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Update Download Paused")
                .setContentText("Network interruption: " + errorMsg + ". Tap to resume.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        if (mNotificationManager != null) {
            mNotificationManager.notify(NOTIF_PROGRESS_ID, failedBuilder.build());
        }

        MAIN_HANDLER.post(() -> Toast.makeText(UpdateDownloadService.this, "Download paused. Will resume from current position.", Toast.LENGTH_SHORT).show());
        stopSelf();
    }

    /**
     * Computes SHA-256 cryptographic hex digest of an APK file to detect tampering (CWE-353).
     */
    public static String calculateSha256(File file) {
        if (file == null || !file.exists()) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) > 0) {
                    digest.update(buf, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error calculating SHA-256 digest: " + e.getMessage());
            return null;
        }
    }
}
