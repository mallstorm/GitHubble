-keepattributes SourceFile,LineNumberTable

# Room 2.6.1 preserves generated database class names, but R8 full mode also needs
# the no-argument constructor invoked reflectively during WorkManager startup.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}

# XrayBridge resolves this gomobile API by class/method name; JNI also calls go.Seq.
# Retain the small binding/runtime API while R8 optimizes the rest of the application.
# These rules intentionally mirror the pinned AAR consumer rules.
-keep class go.** { *; }
-keep class libXray.** { *; }

# Mirror Manager encrypts GitHub Actions secrets with libsodium through JNA (JNI + reflection);
# without these rules the minified release build fails to load them at runtime.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
