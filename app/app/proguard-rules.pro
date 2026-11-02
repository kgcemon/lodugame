# R8 rules for the release build (debug builds are not shrunk).

# Socket.IO / Engine.IO client: event payloads and callbacks are looked up at runtime.
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# OkHttp's optional TLS providers are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Credential Manager finds its Play services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
# Sign in with Google: options and credentials travel through Play services as named types.
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
