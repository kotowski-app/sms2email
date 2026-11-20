# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Keep Hilt
-keep class dagger.hilt.* {*;}
-keep class * extends dagger.hilt.android.internal.builders.ComponentManager.Builder {*;}
-dontwarn dagger.hilt.*
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation interface * {
    @dagger.hilt.android.* <methods>;
}

# Keep JavaMail
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# WorkManager
-keep class androidx.work.** { *; }

# Security crypto
-keep class androidx.security.crypto.** { *; }

# Error Prone annotations (transitive dependency, not needed at runtime)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Aggressive obfuscation settings
-optimizationpasses 3
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt
-repackageclasses ''
-allowaccessmodification

# Strip low-level logs from release builds (per Android logging docs)
-assumenosideeffects class android.util.Log {
  static *** i(...);
  static *** d(...);
  static *** v(...);
  static *** e(...);
  static *** w(...);
  static *** wtf(...);
  static *** isLoggable(...);
}
-maximumremovedandroidloglevel 4
