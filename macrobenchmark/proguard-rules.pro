# Benchmark-specific ProGuard rules

# Keep benchmark classes
-keep class com.example.gloabtranslate.macrobenchmark.** { *; }

# Keep test classes
-keep class androidx.test.** { *; }
-keep class androidx.benchmark.** { *; }

# Keep UI Automator classes
-keep class androidx.test.uiautomator.** { *; }

# Keep JUnit classes
-keep class org.junit.** { *; }

# Keep app classes that are being tested
-keep class com.example.gloabtranslate.MainActivity { *; }
-keep class com.example.gloabtranslate.service.** { *; }

# Keep core module classes
-keep class com.example.gloabtranslate.core.** { *; }

# Keep speech module classes
-keep class com.example.gloabtranslate.speech.** { *; }

# Keep NLP module classes
-keep class com.example.gloabtranslate.nlp.** { *; }

# Keep TTS module classes
-keep class com.example.gloabtranslate.tts.** { *; }

# Benchmark-specific optimizations
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep line numbers for better stack traces
-keepattributes SourceFile,LineNumberTable

# Keep annotations
-keepattributes *Annotation*

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
