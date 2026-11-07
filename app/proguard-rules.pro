-keepattributes SourceFile,LineNumberTable

# XrayBridge resolves this gomobile API by class/method name; JNI also calls go.Seq.
# Retain the small binding/runtime API while R8 optimizes the rest of the application.
# These rules intentionally mirror the pinned AAR consumer rules.
-keep class go.** { *; }
-keep class libXray.** { *; }
