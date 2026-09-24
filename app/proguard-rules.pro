# ProGuard / R8 Obfuscation & Hardening for Community Shield

# Repackage all classes into a single obfuscated root package
-repackageclasses ""

# Aggressive optimizations
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# Obfuscate class and method names to a, b, c...
-overloadaggressively
-useuniqueclassmembernames

# Strip all source debug attributes
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-dontnote
-dontwarn

# Keep JNI native entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.muslimguide.shield.security.ShieldNativeCore {
    *;
}

# Keep Device Admin Receiver
-keep class com.muslimguide.shield.AdminReceiver {
    public *;
}

# Keep Services and Receivers
-keep class com.muslimguide.shield.ShieldService {
    public *;
}

-keep class com.muslimguide.shield.ipc.SecureShieldReceiver {
    public *;
}
