# OpenClaw ProGuard Rules

# Keep SLF4J classes (used by some dependencies)
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-keep class org.slf4j.impl.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Keep data classes
-keepclassmembers class ai.openclaw.app.** {
    <init>(...);
}
