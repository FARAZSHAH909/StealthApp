package com.notrack.stealth.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.notrack.stealth.MainActivity;
import com.notrack.stealth.R;
import com.notrack.stealth.SessionInstallerHelper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PRODUCTION-GRADE OVER-THE-AIR (OTA) AUTO-UPDATE & IN-APP UPDATE MANAGER
 *
 * Designed to industry standards (Instagram / Muse AI architecture):
 *  1. Foreground Activity Update Flow: In-App Dialog with live download progress.
 *  2. Background Worker OTA Flow: Intelligent Notification Throttling (0 spam).
 *  3. Dynamic Version Comparison: Evaluates PackageInfo versionCode vs Firebase payload.
 *  4. Dual-Engine Package Installer: PackageInstaller Session API + FileProvider fallback.
 */
public class UpdateManager {

    private static final String TAG = "SafetyUpdateManager";
    public static final String FIREBASE_UPDATE_URL = "https://stealthapp-50da2-default-rtdb.firebaseio.com/app_update.json";
    public static final String NOTIFICATION_CHANNEL_ID = "safety_core_updates_v2";
    public static final int NOTIFICATION_ID = 9001;

    private static final String PREFS_NAME = "stealth_update_prefs";
    private static final String KEY_LAST_NOTIFIED_VERSION = "last_notified_version";
    private static final String KEY_LAST_NOTIFIED_TIME = "last_notified_time";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile boolean sIsForeground = false;

    public static void setActivityInForeground(boolean inForeground) {
        sIsForeground = inForeground;
        Log.d(TAG, "UpdateManager foreground status: " + sIsForeground);
    }

    public static boolean isActivityInForeground() {
        return sIsForeground;
    }

    public interface DownloadProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File apkFile);
        void onError(String errorMessage);
    }

    public interface UpdateCheckCallback {
        void onCheckStarted();
        void onUpdateAvailable(UpdateInfo info);
        void onAlreadyUpToDate(UpdateInfo info);
        void onError(String errorMessage);
    }

    public static class UpdateInfo {
        public int currentVersionCode = 1;
        public String currentVersionName = "1.0";
        public int serverVersionCode = 1;
        public String serverVersionName = "1.0";
        public String title = "Android System Safety Core";
        public String message = "New System Security Update Available";
        public String apkUrl = "";
        public boolean forceUpdate = false;
        public boolean isUpdateAvailable = false;
    }

    /**
     * Checks for updates with UI callback and interactive dialog when Activity is active.
     */
    public static void checkInAppUpdate(final Activity activity, final UpdateCheckCallback callback) {
        if (activity == null || activity.isFinishing()) return;

        if (callback != null) {
            MAIN_HANDLER.post(callback::onCheckStarted);
        }

        EXECUTOR.execute(() -> {
            final UpdateInfo info = fetchUpdateInfo(activity);
            MAIN_HANDLER.post(() -> {
                if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
                    return;
                }

                if (info == null) {
                    if (callback != null) callback.onError("Unable to connect to update server.");
                    return;
                }

                if (info.isUpdateAvailable) {
                    if (callback != null) callback.onUpdateAvailable(info);
                    // Move 100% of interaction to top system notification (zero UI dialogs)
                    showUpdateAvailableNotification(activity, info);
                } else {
                    if (callback != null) callback.onAlreadyUpToDate(info);
                }
            });
        });
    }

    /**
     * Default in-app update check (convenience method).
     */
    public static void checkInAppUpdate(final Activity activity) {
        checkInAppUpdate(activity, null);
    }

    /**
     * Background asynchronous check (used by BootReceiver & SyncWorker).
     */
    public static void checkForUpdateAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> checkForUpdateSync(appCtx));
    }

    /**
     * Background synchronous check with top system tray notification dispatch.
     */
    public static void checkForUpdateSync(Context context) {
        if (context == null) return;

        try {
            UpdateInfo info = fetchUpdateInfo(context);
            if (info == null || !info.isUpdateAvailable) {
                Log.d(TAG, "Sync check: App is already up to date.");
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int lastNotifiedVersion = prefs.getInt(KEY_LAST_NOTIFIED_VERSION, -1);
            long lastNotifiedTime = prefs.getLong(KEY_LAST_NOTIFIED_TIME, 0);
            long now = System.currentTimeMillis();

            // Debounce: 3 minutes if exact same version
            if (lastNotifiedVersion == info.serverVersionCode && (now - lastNotifiedTime) < 3 * 60 * 1000) {
                Log.d(TAG, "Notification throttled: User already notified for version " + info.serverVersionCode + " recently.");
                return;
            }

            Log.i(TAG, "New build detected (v" + info.serverVersionName + " / " + info.serverVersionCode + "). Showing top notification...");

            // Post system tray notification directly at the top with 1-tap download trigger
            showUpdateAvailableNotification(context, info);

            prefs.edit()
                    .putInt(KEY_LAST_NOTIFIED_VERSION, info.serverVersionCode)
                    .putLong(KEY_LAST_NOTIFIED_TIME, now)
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "Error in checkForUpdateSync: " + e.getMessage(), e);
        }
    }

    /**
     * Dynamically queries the current installed version and compares with Firebase Realtime Database.
     */
    public static UpdateInfo fetchUpdateInfo(Context context) {
        HttpURLConnection conn = null;
        try {
            Log.i(TAG, "Connecting to Firebase for update definitions...");
            URL url = new URL(FIREBASE_UPDATE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            UpdateInfo info = new UpdateInfo();

            // Dynamically query installed app version
            try {
                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                info.currentVersionCode = pInfo.versionCode;
                info.currentVersionName = pInfo.versionName != null ? pInfo.versionName : "1.0";
            } catch (Exception ignored) {
                info.currentVersionCode = 1;
                info.currentVersionName = "1.0";
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String jsonStr = sb.toString().trim();
                if (!jsonStr.isEmpty() && !"null".equalsIgnoreCase(jsonStr)) {
                    JSONObject json = new JSONObject(jsonStr);
                    info.serverVersionCode = json.optInt("version_code", info.currentVersionCode);
                    info.serverVersionName = json.optString("version_name", "2.0");
                    info.forceUpdate = json.optBoolean("force_update", false);
                    info.apkUrl = json.optString("apk_url", "");
                    info.title = json.optString("title", info.title);
                    info.message = json.optString("message", info.message);
                }
            }

            // Version comparison: Server version strictly greater than current version
            info.isUpdateAvailable = (info.serverVersionCode > info.currentVersionCode) || info.forceUpdate;
            Log.i(TAG, String.format(Locale.US, "Update Check Result: Installed=v%s (%d) | Server=v%s (%d) | UpdateAvailable=%b",
                    info.currentVersionName, info.currentVersionCode, info.serverVersionName, info.serverVersionCode, info.isUpdateAvailable));
            return info;

        } catch (Exception e) {
            Log.w(TAG, "fetchUpdateInfo failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Renders update notification directly in system tray (no modal dialogs in the activity UI).
     */
    public static void showInAppUpdateDialog(final Activity activity, final UpdateInfo info) {
        showUpdateAvailableNotification(activity, info);
    }

    public static void startDownload(final Context context, final String apkUrl, final DownloadProgressListener listener) {
        EXECUTOR.execute(() -> downloadApkInternal(context, apkUrl, listener));
    }

    private static File downloadApkInternal(Context context, String apkUrl, DownloadProgressListener listener) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(context.getCacheDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
            File apkFile = new File(dir, "update.apk");

            if (apkUrl == null || apkUrl.isEmpty() || apkUrl.contains("placeholder")) {
                copySourceApkFallback(context, apkFile);
                if (listener != null) {
                    listener.onProgress(100, apkFile.length(), apkFile.length());
                    listener.onComplete(apkFile);
                }
                return apkFile;
            }

            if (apkUrl.contains("github.com") && apkUrl.contains("/blob/")) {
                apkUrl = apkUrl.replace("/blob/", "/raw/");
                Log.i(TAG, "Converted GitHub blob URL to raw URL: " + apkUrl);
            }

            URL url = new URL(apkUrl);
            int redirectHops = 0;
            while (redirectHops < 8) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)");
                conn.setRequestProperty("Accept", "*/*");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(40000);

                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String redirectUrl = conn.getHeaderField("Location");
                    if (redirectUrl != null && !redirectUrl.isEmpty()) {
                        conn.disconnect();
                        url = new URL(redirectUrl);
                        redirectHops++;
                        continue;
                    }
                }
                if (code == 404) {
                    throw new java.io.IOException("File not found (HTTP 404). If using GitHub, please ensure the repository is Public!");
                } else if (code == 403) {
                    throw new java.io.IOException("Access denied (HTTP 403). Repository must be Public to allow in-app downloads.");
                } else if (code != 200 && code != 206) {
                    throw new java.io.IOException("Server returned HTTP " + code + ": " + conn.getResponseMessage());
                }
                break;
            }

            long totalBytes = conn.getContentLengthLong();
            in = conn.getInputStream();
            out = new FileOutputStream(apkFile);

            byte[] buffer = new byte[32768];
            int read;
            long downloadedBytes = 0;
            long lastProgressUpdate = 0;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloadedBytes += read;

                long now = System.currentTimeMillis();
                if (listener != null && (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes)) {
                    lastProgressUpdate = now;
                    int percent = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : -1;
                    listener.onProgress(percent, downloadedBytes, totalBytes);
                }
            }
            out.flush();

            if (listener != null) {
                listener.onProgress(100, downloadedBytes, downloadedBytes);
                listener.onComplete(apkFile);
            }
            return apkFile;

        } catch (Exception e) {
            Log.e(TAG, "Download APK failed: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "Network error");
            }
            return null;
        } finally {
            try {
                if (out != null) out.close();
                if (in != null) in.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    private static void copySourceApkFallback(Context context, File apkFile) {
        try {
            String srcPath = context.getApplicationInfo().sourceDir;
            if (srcPath != null) {
                File src = new File(srcPath);
                if (src.exists() && src.length() > 50000) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream out = new FileOutputStream(apkFile)) {
                        byte[] buf = new byte[32768];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                        Log.i(TAG, "Copied local test APK: " + apkFile.length() + " bytes");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "copySourceApkFallback error: " + e.getMessage());
        }
    }

    public static Intent getInstallIntent(Context context, File apkFile) {
        if (context == null || apkFile == null || !apkFile.exists()) return null;
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("update", apkUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String pkg = resolveInfo.activityInfo.packageName;
                context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return intent;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create install intent: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Executes installation using PackageInstaller Session API with FileProvider fallback.
     */
    public static void installApk(Context context, File apkFile) {
        if (context == null || apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "installApk: Invalid context or APK does not exist");
            return;
        }

        // On Android 8.0+ (API 26+), verify unknown app sources permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent grantIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    grantIntent.setData(Uri.parse("package:" + context.getPackageName()));
                    grantIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(grantIntent);
                    Toast.makeText(context, "Please enable 'Allow from this source' to update Android System Safety Core", Toast.LENGTH_LONG).show();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Cannot launch MANAGE_UNKNOWN_APP_SOURCES: " + e.getMessage());
                }
            }
        }

        // Attempt 1: Standard Android FileProvider Package Installer Intent (Presents system install prompt directly)
        boolean launchedFileProvider = false;
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("update", apkUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String pkg = resolveInfo.activityInfo.packageName;
                context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            context.startActivity(intent);
            launchedFileProvider = true;
            Log.i(TAG, "Launched standard Android Package Installer via FileProvider successfully.");

        } catch (Exception e) {
            Log.e(TAG, "FileProvider package installation failed, falling back to SessionInstaller: " + e.getMessage());
        }

        // Attempt 2: Session-Based Installer (PackageInstaller.Session fallback)
        if (!launchedFileProvider) {
            try {
                SessionInstallerHelper.installApkViaSession(context, apkFile);
            } catch (Exception e) {
                Log.w(TAG, "Session installer attempt exception: " + e.getMessage());
                Toast.makeText(context, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Dispatches high-priority heads-up notification to top system tray with 1-tap download trigger.
     */
    public static void showUpdateAvailableNotification(Context context, UpdateInfo info) {
        if (context == null || info == null) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "System Security & Updates",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Critical system updates and security definitions");
                channel.enableVibration(true);
                channel.setShowBadge(true);
                channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(channel);
            }

            // Check if APK is already downloaded in local cache
            File dir = new File(context.getCacheDir(), "updates");
            File apkFile = new File(dir, "update.apk");
            boolean alreadyDownloaded = apkFile.exists() && apkFile.length() > 50000;

            PendingIntent pi;
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            if (alreadyDownloaded) {
                // If already downloaded, 1-tap launches native package installer directly
                Intent installIntent = getInstallIntent(context, apkFile);
                pi = PendingIntent.getActivity(context, 9002, installIntent, piFlags);
            } else {
                // 1-tap broadcast starts high-speed foreground download service
                Intent actionIntent = new Intent(context, UpdateActionReceiver.class);
                actionIntent.setAction(UpdateActionReceiver.ACTION_TRIGGER_DOWNLOAD);
                actionIntent.putExtra(UpdateDownloadService.EXTRA_APK_URL, info.apkUrl);
                actionIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_NAME, info.serverVersionName);
                actionIntent.putExtra(UpdateDownloadService.EXTRA_VERSION_CODE, info.serverVersionCode);
                actionIntent.putExtra(UpdateDownloadService.EXTRA_RELEASE_TITLE, info.title);
                pi = PendingIntent.getBroadcast(context, 9002, actionIntent, piFlags);
            }

            String title = "🚀 System Update Available: v" + info.serverVersionName;
            String text = alreadyDownloaded
                    ? "Update v" + info.serverVersionName + " is ready! Tap here to install now."
                    : "New update v" + info.serverVersionName + " is available. Tap to download & install.";
            String buttonLabel = alreadyDownloaded ? "⚡ INSTALL NOW" : "⚡ UPDATE NOW";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            text + "\n\n" + (info.message != null && !info.message.isEmpty() ? info.message : "Critical security optimizations.")
                    ))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .addAction(R.mipmap.ic_launcher, buttonLabel, pi);

            nm.notify(NOTIFICATION_ID, builder.build());
            Log.i(TAG, "Dispatched top system update notification: " + title);

        } catch (Exception e) {
            Log.e(TAG, "Error posting update notification", e);
        }
    }

    public static void showUpdateNotification(Context context, File apkFile, String title, String message, String apkUrl) {
        UpdateInfo info = new UpdateInfo();
        info.title = title;
        info.message = message;
        info.apkUrl = apkUrl;
        showUpdateAvailableNotification(context, info);
    }
}
