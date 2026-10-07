# SoundBite ProGuard / R8 Rules
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**
-keepattributes *Annotation*

# TensorFlow Lite / LiteRT
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }

