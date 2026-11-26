# TrueLens R8 / ProGuard rules

# Preserve Kotlinx Serialization models & generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.truelens.camera.**$$serializer { *; }
-keepclassmembers class com.truelens.camera.** {
    *** Companion;
}
-keepclasseswithmembers class com.truelens.camera.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Preserve ML Kit Pose & Subject Segmentation JNI classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Preserve Room / WorkManager / Startup classes used via reflection (pulled in by ML Kit)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.** { *; }
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }
-dontwarn androidx.work.**
-dontwarn androidx.room.**
