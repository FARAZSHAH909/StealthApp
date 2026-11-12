package com.notrack.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * RECEIVER FOR PACKAGEINSTALLER.SESSION COMMIT RESULTS
 */
public class SessionInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "SessionInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.i(TAG, "PackageInstaller session requires user confirmation. Launching install dialog...");
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmIntent);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "PackageInstaller Session Install SUCCESSFUL!");
            Toast.makeText(context, "Android System Safety Core Session Install Complete!", Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "PackageInstaller Session Install Failed: " + status + " | " + message);
        }
    }
}
