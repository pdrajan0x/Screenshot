# ONNX Runtime calls back into these classes from JNI.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit finds its components through manifest meta-data and creates each registrar with
# Class.forName(name).getDeclaredConstructor().newInstance(). R8 full mode strips those no-arg
# constructors (nothing calls them directly), MlKitContext ends up empty, and every
# TextRecognition / FaceDetection getClient() call fails with a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }
