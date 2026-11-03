# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    companion object;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
