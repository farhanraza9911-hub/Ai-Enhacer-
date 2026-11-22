# JNI se call hone wale methods/classes ko R8 rename ya hata na de
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.farhan.aienhancer.NativeEnhancer {
    native <methods>;
}
-keep interface com.farhan.aienhancer.ProgressListener {
    *;
}

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
