# Global Translate App ProGuard Rules

# Keep application class
-keep public class * extends android.app.Application

# Keep all model classes
-keep class com.example.gloabtranslate.core.data.models.** { *; }
-keep class com.example.gloabtranslate.core.data.repository.** { *; }

# Keep all core functionality
-keep class com.example.gloabtranslate.core.** { *; }

# Keep translation services
-keep class com.example.gloabtranslate.nlp.** { *; }
-keep class com.example.gloabtranslate.speech.** { *; }
-keep class com.example.gloabtranslate.tts.** { *; }

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Media3
-keep class androidx.media3.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}