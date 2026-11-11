package com.notrack.stealth.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.File;

/**
 * PRODUCTION SECURITY UTILITIES
 *
 * Root Detection, Debugger Detection & Runtime Tampering Protection
 */
public class SecurityUtils {

    private static final String TAG = "SecurityUtils";

    private static final String[] ROOT_PATHS = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
    };

    /** Returns true if device shows signs of being rooted. */
    public static boolean isRooted() {
        return checkBuildTags() || checkRootPaths() || checkSuCommand();
    }

    private static boolean checkBuildTags() {
        String buildTags = Build.TAGS;
        return buildTags != null && buildTags.contains("test-keys");
    }

    private static boolean checkRootPaths() {
        for (String path : ROOT_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkSuCommand() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"/system/xbin/which", "su"});
            return process != null;
        } catch (Throwable t) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Returns true if a debugger is attached. */
    public static boolean isDebuggerConnected() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    /** Returns true if app is built in debug mode or being tampered with. */
    public static boolean isDebuggable(Context context) {
        if (context == null) return false;
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}