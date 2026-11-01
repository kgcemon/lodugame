# R8 rules for the release build (debug builds are not shrunk).

# Socket.IO / Engine.IO client: event payloads and callbacks are looked up at runtime.
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# OkHttp's optional TLS providers are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
