# 🕵️ Stealth Notification Monitor

Android app jo background mein silently har notification (WhatsApp, SMS, Telegram…)
padhta hai aur **phone ke andar hi** store karta hai (koi Firebase/internet nahi).
Home screen par icon nahi dikhta.

## 📁 Folder Structure

```
StealthNotificationMonitor/
├── build.gradle            → root build config
├── settings.gradle         → project name
└── app/
    ├── build.gradle        → dependencies (ab Firebase nahi)
    └── src/main/
        ├── AndroidManifest.xml          → NO-ICON trick yahan hai
        └── java/com/notrack/stealth/
            ├── StealthService.java  → GHOST SERVICE (notification sunta + file mein likhta hai)
            ├── MainActivity.java    → setup screen + DATA VIEWER (data yahan dekho)
            └── BootReceiver.java    → phone restart par service wapas on
```

## 🛠️ Setup

### 1. APK Banao
Android Studio mein `StealthNotificationMonitor` folder kholo, **Build → Build APK**.
`app/build/outputs/apk/debug/app-debug.apk` phone par install karo.

### 2. Pehli Baar Setup (ek baar hi)
1. **Settings → Apps → "Stealth Monitor" → Open**
2. "Notification access" mein **Stealth Monitor ON** karo
3. "Allow battery optimization?" → **Allow**
4. Done. Icon kabhi nahi dikhega.

Xiaomi/Redmi: **Settings → Battery → Stealth Monitor → No restrictions**
Huawei: **Settings → Battery → Stealth Monitor → Unrestricted**

## 📋 Data Kahan Dikhega?

**Option A (app khol ke):** Settings → Apps → "Stealth Monitor" → Open
→ saara captured data screen par aa jaayega.

**Option B (raw file):** Phone ki internal storage mein:
`/data/data/com.notrack.stealth/files/notifications.txt`
(USB debugging se ya "Files" app ke "show hidden" se dekh sakte ho.)

Har line aisi dikhegi:
```
05/11/2026 14:32:10 | com.whatsapp | +91 98765 43210 | kal milte hain office par
```
→ time | kaunsi app | sender | message

## 🔋 Battery kaise bachti hai
- Koi Firebase, koi internet call, koi cloud — sab local
- Notification aane tak service soti hai, aane par 1 sec mein file likh ke phir soti hai
- Battery optimization "Ignore" kar diya hai, isliye Android ise kill nahi karta