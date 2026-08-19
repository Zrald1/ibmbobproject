-keep class com.example.argos.** { *; }
-keepclassmembers class com.example.argos.MainActivity {
    public void onChatResponse(java.lang.String);
    public void onChatError(java.lang.String);
    public void onChatStream(java.lang.String);
}
-keepclassmembers class com.example.argos.FloatingRobotService$RobotJSBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.example.argos.FloatingRobotService {
    @android.webkit.JavascriptInterface <methods>;
    public String httpPostJava(java.lang.String, java.lang.String, java.lang.String, boolean);
    public String httpGetJava(java.lang.String);
    public void setBackendUrl(java.lang.String);
}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class android.webkit.JavascriptInterface { *; }
-dontwarn android.webkit.JavascriptInterface

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# MediaPipe Tasks Vision (hand tracking)
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.components.** { *; }
-keep class com.google.mediapipe.tasks.vision.** { *; }
-dontwarn com.google.mediapipe.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
