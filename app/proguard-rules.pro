# ProGuard & R8 Obfuscation Rules for Android System Safety Core

# Preserve Room Database, DAOs and Entities
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.notrack.stealth.db.** { *; }

# Preserve WorkManager Worker Classes
-keep public class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.notrack.stealth.sync.** { *; }

# Preserve Firebase RTDB & Auth Classes
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepclassmembers class * {
    @com.google.firebase.database.IgnoreExtraProperties <fields>;
    @com.google.firebase.database.PropertyName <methods>;
    @com.google.firebase.database.PropertyName <fields>;
}

# Preserve Security, Crypto and Envelope Classes
-keep class com.notrack.stealth.security.** { *; }
-keep class com.notrack.stealth.backup.** { *; }
-keep class com.notrack.stealth.update.** { *; }
-keep class com.notrack.stealth.watchdog.** { *; }

# Preserve Activities and Services
-keep public class com.notrack.stealth.MainActivity { *; }
-keep public class com.notrack.stealth.StealthService { *; }
-keep public class com.notrack.stealth.StealthApp { *; }
