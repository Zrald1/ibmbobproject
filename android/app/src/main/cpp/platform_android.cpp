#include "platform.h"
#include <android/log.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <cstring>
#include <fstream>

#define TAG "Argos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// HTTP is handled via JNI -> Java HttpURLConnection (supports HTTPS)
// We just need the JNI environment here

#include <jni.h>

namespace argos {

static std::string s_appDataDir = "/data/data/com.example.argos/files";

void setAppDataDir(const char* dir) {
    if (dir) s_appDataDir = dir;
}

std::string getAppDataDir() {
    return s_appDataDir;
}

void log(const char* message) {
    LOGI("%s", message);
}

// JNI VM pointer set by native_main.cpp
static JavaVM* s_jvm = nullptr;
static jobject s_service = nullptr;

void setJniForHttp(void* jvm, void* service) {
    s_jvm = (JavaVM*)jvm;
    s_service = (jobject)service;
}

// Call Java's httpPostJava method via JNI
static std::string callJavaHttpPost(const std::string& url, const std::string& headers,
                                     const std::string& body, bool stream) {
    if (!s_jvm || !s_service) {
        LOGE("JNI not initialized for HTTP");
        return "[Error: JNI not initialized]";
    }
    
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) {
        LOGE("Failed to get JNIEnv");
        return "[Error: Failed to get JNIEnv]";
    }
    
    jclass cls = env->GetObjectClass(s_service);
    if (!cls) {
        LOGE("Failed to get service class");
        if (attached) s_jvm->DetachCurrentThread();
        return "[Error: Failed to get service class]";
    }
    
    jmethodID mid = env->GetMethodID(cls, "httpPostJava", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    if (!mid) {
        LOGE("Failed to find httpPostJava method");
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "[Error: httpPostJava method not found]";
    }
    
    jstring jurl = env->NewStringUTF(url.c_str());
    jstring jheaders = env->NewStringUTF(headers.c_str());
    jstring jbody = env->NewStringUTF(body.c_str());
    jboolean jstream = stream ? JNI_TRUE : JNI_FALSE;
    
    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, jurl, jheaders, jbody, jstream);
    
    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) {
            result = chars;
            env->ReleaseStringUTFChars(jresult, chars);
        }
        env->DeleteLocalRef(jresult);
    }
    
    env->DeleteLocalRef(jurl);
    env->DeleteLocalRef(jheaders);
    env->DeleteLocalRef(jbody);
    env->DeleteLocalRef(cls);
    
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (result.empty()) result = "[Error: Java exception in HTTP request]";
    }
    
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

int64_t getTimeMs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

std::string httpPost(const std::string& url, const std::string& headers, const std::string& body) {
    LOGI("httpPost via Java: %s", url.c_str());
    std::string result = callJavaHttpPost(url, headers, body, false);
    LOGI("httpPost result len=%zu", result.size());
    return result;
}

std::string httpPostStream(const std::string& url, const std::string& headers,
                           const std::string& body,
                           std::function<bool(const std::string&)> callback) {
    LOGI("httpPostStream via Java: %s", url.c_str());
    std::string result = callJavaHttpPost(url, headers, body, true);
    LOGI("httpPostStream result len=%zu", result.size());
    if (callback && !result.empty() && result[0] != '[') {
        callback(result);
    }
    return result;
}

// Generic JNI helper: call a Java method on s_service that takes one String arg and returns String
static std::string callJavaStringMethod(const char* methodName, const char* sig, const std::string& arg) {
    if (!s_jvm || !s_service) {
        return "{\"error\":\"JNI not initialized\"}";
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) {
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Failed to get service class\"}";
    }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jarg = env->NewStringUTF(arg.c_str());
    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, jarg);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) {
            result = chars;
            env->ReleaseStringUTFChars(jresult, chars);
        }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(jarg);
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (result.empty()) result = "{\"error\":\"Java exception in " + std::string(methodName) + "\"}";
    }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

// Generic JNI helper: call a Java method on s_service that takes one int arg and returns String
static std::string callJavaIntMethod(const char* methodName, const char* sig, int arg) {
    if (!s_jvm || !s_service) {
        return "{\"error\":\"JNI not initialized\"}";
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) {
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Failed to get service class\"}";
    }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, (jint)arg);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) {
            result = chars;
            env->ReleaseStringUTFChars(jresult, chars);
        }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (result.empty()) result = "{\"error\":\"Java exception in " + std::string(methodName) + "\"}";
    }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

// Generic JNI helper: call a Java method on s_service with no args, returns String
static std::string callJavaNoArgMethod(const char* methodName, const char* sig) {
    if (!s_jvm || !s_service) {
        return "{\"error\":\"JNI not initialized\"}";
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) {
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Failed to get service class\"}";
    }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) {
            result = chars;
            env->ReleaseStringUTFChars(jresult, chars);
        }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (result.empty()) result = "{\"error\":\"Java exception in " + std::string(methodName) + "\"}";
    }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

// ── Browser / Screen interaction platform functions ──

std::string openUrl(const std::string& url) {
    LOGI("openUrl: %s", url.c_str());
    return callJavaStringMethod("openUrlJava", "(Ljava/lang/String;)Ljava/lang/String;", url);
}

std::string getScreenText() {
    LOGI("getScreenText");
    return callJavaNoArgMethod("getScreenTextJava", "()Ljava/lang/String;");
}

std::string getActiveApp() {
    LOGI("getActiveApp");
    return callJavaNoArgMethod("getActiveAppJava", "()Ljava/lang/String;");
}

std::string clickText(const std::string& text) {
    LOGI("clickText: %s", text.c_str());
    return callJavaStringMethod("clickTextJava", "(Ljava/lang/String;)Ljava/lang/String;", text);
}

std::string typeText(const std::string& text) {
    LOGI("typeText: %s", text.c_str());
    return callJavaStringMethod("typeTextJava", "(Ljava/lang/String;)Ljava/lang/String;", text);
}

std::string scrollScreen(int direction) {
    LOGI("scrollScreen: %d", direction);
    return callJavaIntMethod("scrollScreenJava", "(I)Ljava/lang/String;", direction);
}

// ── UI Inspection & Automation ──

std::string getUITree(int maxDepth) {
    LOGI("getUITree: maxDepth=%d", maxDepth);
    return callJavaIntMethod("getUITreeJava", "(I)Ljava/lang/String;", maxDepth);
}

// Two-string-arg JNI helper for performUIAction
static std::string callJavaTwoStringIntMethod(const char* methodName, const char* sig,
                                               int arg1, const std::string& arg2, const std::string& arg3) {
    if (!s_jvm || !s_service) return "{\"error\":\"JNI not initialized\"}";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) { if (attached) s_jvm->DetachCurrentThread(); return "{\"error\":\"No class\"}"; }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jarg2 = env->NewStringUTF(arg2.c_str());
    jstring jarg3 = env->NewStringUTF(arg3.c_str());
    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, (jint)arg1, jarg2, jarg3);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) { result = chars; env->ReleaseStringUTFChars(jresult, chars); }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(jarg2);
    env->DeleteLocalRef(jarg3);
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

std::string performUIAction(int elementId, const std::string& action, const std::string& extra) {
    LOGI("performUIAction: id=%d action=%s extra=%s", elementId, action.c_str(), extra.c_str());
    return callJavaTwoStringIntMethod("performUIActionJava", "(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                                      elementId, action, extra);
}

std::string takeScreenshot(const std::string& savePath) {
    LOGI("takeScreenshot: path=%s", savePath.c_str());
    return callJavaStringMethod("takeScreenshotJava", "(Ljava/lang/String;)Ljava/lang/String;", savePath);
}

std::string getNotificationsList() {
    LOGI("getNotificationsList");
    return callJavaNoArgMethod("getNotificationsJava", "()Ljava/lang/String;");
}

// Two-int-one-string JNI helper for replyToNotification
static std::string callJavaIntStringMethod(const char* methodName, const char* sig,
                                            int arg1, const std::string& arg2) {
    if (!s_jvm || !s_service) return "{\"error\":\"JNI not initialized\"}";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) { if (attached) s_jvm->DetachCurrentThread(); return "{\"error\":\"No class\"}"; }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jarg2 = env->NewStringUTF(arg2.c_str());
    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, (jint)arg1, jarg2);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) { result = chars; env->ReleaseStringUTFChars(jresult, chars); }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(jarg2);
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

std::string replyToNotificationByIdx(int index, const std::string& message) {
    LOGI("replyToNotificationByIdx: idx=%d msg=%s", index, message.c_str());
    return callJavaIntStringMethod("replyToNotificationJava", "(ILjava/lang/String;)Ljava/lang/String;", index, message);
}

// ── Gesture-based UI automation JNI implementations ──

// Helper for calling Java methods with 2 int args returning String
static std::string callJavaTwoIntMethod(const char* methodName, const char* sig, int arg1, int arg2) {
    if (!s_jvm || !s_service) return "{\"error\":\"JNI not initialized\"}";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) { if (attached) s_jvm->DetachCurrentThread(); return "{\"error\":\"No class\"}"; }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid, (jint)arg1, (jint)arg2);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) { result = chars; env->ReleaseStringUTFChars(jresult, chars); }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

// Helper for calling Java methods with 5 int args returning String
static std::string callJavaFiveIntMethod(const char* methodName, const char* sig,
                                          int a1, int a2, int a3, int a4, int a5) {
    if (!s_jvm || !s_service) return "{\"error\":\"JNI not initialized\"}";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) { if (attached) s_jvm->DetachCurrentThread(); return "{\"error\":\"No class\"}"; }
    jmethodID mid = env->GetMethodID(cls, methodName, sig);
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "{\"error\":\"Method not found: " + std::string(methodName) + "\"}";
    }

    jstring jresult = (jstring) env->CallObjectMethod(s_service, mid,
        (jint)a1, (jint)a2, (jint)a3, (jint)a4, (jint)a5);

    std::string result;
    if (jresult) {
        const char* chars = env->GetStringUTFChars(jresult, nullptr);
        if (chars) { result = chars; env->ReleaseStringUTFChars(jresult, chars); }
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

std::string clickAtPoint(int x, int y) {
    LOGI("clickAtPoint: %d,%d", x, y);
    return callJavaTwoIntMethod("clickAtPointJava", "(II)Ljava/lang/String;", x, y);
}

std::string longPressAtPoint(int x, int y) {
    LOGI("longPressAtPoint: %d,%d", x, y);
    return callJavaTwoIntMethod("longPressAtPointJava", "(II)Ljava/lang/String;", x, y);
}

std::string swipeGesture(int x1, int y1, int x2, int y2, int durationMs) {
    LOGI("swipeGesture: %d,%d -> %d,%d dur=%d", x1, y1, x2, y2, durationMs);
    return callJavaFiveIntMethod("swipeJava", "(IIIII)Ljava/lang/String;", x1, y1, x2, y2, durationMs);
}

std::string swipeUp() {
    LOGI("swipeUp");
    return callJavaNoArgMethod("swipeUpJava", "()Ljava/lang/String;");
}

std::string swipeDown() {
    LOGI("swipeDown");
    return callJavaNoArgMethod("swipeDownJava", "()Ljava/lang/String;");
}

std::string swipeLeft() {
    LOGI("swipeLeft");
    return callJavaNoArgMethod("swipeLeftJava", "()Ljava/lang/String;");
}

std::string swipeRight() {
    LOGI("swipeRight");
    return callJavaNoArgMethod("swipeRightJava", "()Ljava/lang/String;");
}

std::string smartClick(int x, int y) {
    LOGI("smartClick: %d,%d", x, y);
    return callJavaTwoIntMethod("smartClickJava", "(II)Ljava/lang/String;", x, y);
}

std::string smartLongPress(int x, int y) {
    LOGI("smartLongPress: %d,%d", x, y);
    return callJavaTwoIntMethod("smartLongPressJava", "(II)Ljava/lang/String;", x, y);
}

std::string getClickableElements() {
    LOGI("getClickableElements");
    return callJavaNoArgMethod("getClickableElementsJava", "()Ljava/lang/String;");
}

std::string getScreenSize() {
    LOGI("getScreenSize");
    return callJavaNoArgMethod("getScreenSizeJava", "()Ljava/lang/String;");
}

// ── Voice / Audio JNI implementations ──

std::string recordAudioJava(int durationSeconds) {
    LOGI("recordAudioJava: duration=%d", durationSeconds);
    if (!s_jvm || !s_service) return "";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "";

    jclass cls = env->GetObjectClass(s_service);
    if (!cls) { if (attached) s_jvm->DetachCurrentThread(); return ""; }
    jmethodID mid = env->GetMethodID(cls, "recordAudioJava", "(I)[B");
    if (!mid) {
        env->DeleteLocalRef(cls);
        if (attached) s_jvm->DetachCurrentThread();
        return "";
    }

    jbyteArray jresult = (jbyteArray) env->CallObjectMethod(s_service, mid, (jint)durationSeconds);

    std::string result;
    if (jresult) {
        jsize len = env->GetArrayLength(jresult);
        jbyte* bytes = env->GetByteArrayElements(jresult, nullptr);
        if (bytes && len > 0) {
            result.assign((const char*)bytes, len);
        }
        env->ReleaseByteArrayElements(jresult, bytes, JNI_ABORT);
        env->DeleteLocalRef(jresult);
    }
    env->DeleteLocalRef(cls);

    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

std::string ttsSpeakJava(const std::string& text) {
    LOGI("ttsSpeakJava: %s", text.c_str());
    return callJavaStringMethod("ttsSpeakJava", "(Ljava/lang/String;)Ljava/lang/String;", text);
}

std::string ttsStopJava() {
    LOGI("ttsStopJava");
    return callJavaNoArgMethod("ttsStopJava", "()Ljava/lang/String;");
}

std::string ttsIsSpeakingJava() {
    LOGI("ttsIsSpeakingJava");
    return callJavaNoArgMethod("ttsIsSpeakingJava", "()Ljava/lang/String;");
}

// ── System tool platform functions (via JNI to Java) ──

std::string openFile(const std::string& path) {
    LOGI("openFile: %s", path.c_str());
    return callJavaStringMethod("openFileJava", "(Ljava/lang/String;)Ljava/lang/String;", path);
}

std::string writeFile(const std::string& args) {
    LOGI("writeFile: %s", args.c_str());
    return callJavaStringMethod("writeFileJava", "(Ljava/lang/String;)Ljava/lang/String;", args);
}

std::string runApp(const std::string& packageName) {
    LOGI("runApp: %s", packageName.c_str());
    return callJavaStringMethod("runAppJava", "(Ljava/lang/String;)Ljava/lang/String;", packageName);
}

std::string clipboardCopy(const std::string& text) {
    LOGI("clipboardCopy: len=%zu", text.size());
    return callJavaStringMethod("clipboardJava", "(Ljava/lang/String;)Ljava/lang/String;", text);
}

std::string setVolume(int level) {
    LOGI("setVolume: %d", level);
    return callJavaIntMethod("setVolumeJava", "(I)Ljava/lang/String;", level);
}

std::string showNotification(const std::string& message) {
    LOGI("showNotification: %s", message.c_str());
    return callJavaStringMethod("notifyJava", "(Ljava/lang/String;)Ljava/lang/String;", message);
}

// ── Robot Control (3D floating robot) ──

void robotSetExpression(const std::string& expression) {
    LOGI("robotSetExpression: %s", expression.c_str());
    callJavaStringMethod("robotSetExpression", "(Ljava/lang/String;)V", expression);
}

void robotMoveTo(float x, float y) {
    LOGI("robotMoveTo: %.0f,%.0f", x, y);
    // Use direct JNI call since we need two float params
    if (!s_jvm || !s_service) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(s_service);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "robotMoveTo", "(FF)V");
        if (mid) env->CallVoidMethod(s_service, mid, x, y);
        env->DeleteLocalRef(cls);
    }
    if (attached) s_jvm->DetachCurrentThread();
}

void robotSetState(const std::string& state) {
    LOGI("robotSetState: %s", state.c_str());
    callJavaStringMethod("robotSetState", "(Ljava/lang/String;)V", state);
}

std::string robotGetPosition() {
    LOGI("robotGetPosition");
    if (!s_jvm || !s_service) return "{\"error\":\"JNI not initialized\"}";
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return "{\"error\":\"Failed to get JNIEnv\"}";
    std::string result;
    jclass cls = env->GetObjectClass(s_service);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "robotGetPosition", "()Ljava/lang/String;");
        if (mid) {
            jstring jr = (jstring) env->CallObjectMethod(s_service, mid);
            if (jr) {
                const char* chars = env->GetStringUTFChars(jr, nullptr);
                if (chars) { result = chars; env->ReleaseStringUTFChars(jr, chars); }
                env->DeleteLocalRef(jr);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) { env->ExceptionClear(); }
    if (attached) s_jvm->DetachCurrentThread();
    return result;
}

void robotSetZoom(float scale) {
    LOGI("robotSetZoom: %.2f", scale);
    if (!s_jvm || !s_service) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(s_service);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "robotSetZoom", "(F)V");
        if (mid) env->CallVoidMethod(s_service, mid, scale);
        env->DeleteLocalRef(cls);
    }
    if (attached) s_jvm->DetachCurrentThread();
}

void robotResetZoom() {
    LOGI("robotResetZoom");
    callJavaStringMethod("robotResetZoom", "()V", "");
}

void robotBlinkTo(float x, float y) {
    LOGI("robotBlinkTo: %.0f,%.0f", x, y);
    if (!s_jvm || !s_service) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        s_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return;
    jclass cls = env->GetObjectClass(s_service);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, "robotBlinkTo", "(FF)V");
        if (mid) env->CallVoidMethod(s_service, mid, x, y);
        env->DeleteLocalRef(cls);
    }
    if (attached) s_jvm->DetachCurrentThread();
}

std::string observeScreen() {
    LOGI("observeScreen");
    return callJavaNoArgMethod("observeScreenJava", "()Ljava/lang/String;");
}

// ── Phone Automation Tools (Play Store Compliant) ──

std::string dialPhoneNumber(const std::string& number) {
    LOGI("dialPhoneNumber: %s", number.c_str());
    return callJavaStringMethod("dialPhoneNumberJava", "(Ljava/lang/String;)Ljava/lang/String;", number);
}

std::string sendSmsViaIntent(const std::string& number, const std::string& message) {
    LOGI("sendSmsViaIntent: %s", number.c_str());
    // Pack number|message into single string arg
    std::string packed = number + "|" + message;
    return callJavaStringMethod("sendSmsJava", "(Ljava/lang/String;)Ljava/lang/String;", packed);
}

std::string searchContacts(const std::string& query) {
    LOGI("searchContacts: %s", query.c_str());
    return callJavaStringMethod("searchContactsJava", "(Ljava/lang/String;)Ljava/lang/String;", query);
}

std::string createCalendarEvent(const std::string& title, const std::string& description, long startMillis, long endMillis) {
    LOGI("createCalendarEvent: %s", title.c_str());
    // Pack title|description|start|end into single string
    std::string packed = title + "|" + description + "|" + std::to_string(startMillis) + "|" + std::to_string(endMillis);
    return callJavaStringMethod("createCalendarEventJava", "(Ljava/lang/String;)Ljava/lang/String;", packed);
}

std::string readCalendarEvents(int daysAhead) {
    LOGI("readCalendarEvents: %d days", daysAhead);
    return callJavaIntMethod("readCalendarEventsJava", "(I)Ljava/lang/String;", daysAhead);
}

std::string setTimer(int seconds, const std::string& label) {
    LOGI("setTimer: %ds label=%s", seconds, label.c_str());
    std::string packed = std::to_string(seconds) + "|" + label;
    return callJavaStringMethod("setTimerJava", "(Ljava/lang/String;)Ljava/lang/String;", packed);
}

std::string setAlarm(int hour, int minute, const std::string& label) {
    LOGI("setAlarm: %d:%d label=%s", hour, minute, label.c_str());
    std::string packed = std::to_string(hour) + ":" + std::to_string(minute) + "|" + label;
    return callJavaStringMethod("setAlarmJava", "(Ljava/lang/String;)Ljava/lang/String;", packed);
}

std::string getBatteryStatus() {
    LOGI("getBatteryStatus");
    return callJavaNoArgMethod("getBatteryStatusJava", "()Ljava/lang/String;");
}

std::string toggleFlashlight(bool on) {
    LOGI("toggleFlashlight: %d", on ? 1 : 0);
    return callJavaStringMethod("toggleFlashlightJava", "(Ljava/lang/String;)Ljava/lang/String;", on ? "on" : "off");
}

std::string openMaps(const std::string& query) {
    LOGI("openMaps: %s", query.c_str());
    return callJavaStringMethod("openMapsJava", "(Ljava/lang/String;)Ljava/lang/String;", query);
}

std::string startNavigation(const std::string& destination) {
    LOGI("startNavigation: %s", destination.c_str());
    return callJavaStringMethod("startNavigationJava", "(Ljava/lang/String;)Ljava/lang/String;", destination);
}

std::string shareContent(const std::string& text) {
    LOGI("shareContent: len=%zu", text.size());
    return callJavaStringMethod("shareContentJava", "(Ljava/lang/String;)Ljava/lang/String;", text);
}

std::string playMusic(const std::string& query) {
    LOGI("playMusic: %s", query.c_str());
    return callJavaStringMethod("playMusicJava", "(Ljava/lang/String;)Ljava/lang/String;", query);
}

std::string openSettings(const std::string& settingType) {
    LOGI("openSettings: %s", settingType.c_str());
    return callJavaStringMethod("openSettingsJava", "(Ljava/lang/String;)Ljava/lang/String;", settingType);
}

std::string readClipboard() {
    LOGI("readClipboard");
    return callJavaNoArgMethod("readClipboardJava", "()Ljava/lang/String;");
}

std::string getLocation() {
    LOGI("getLocation");
    return callJavaNoArgMethod("getLocationJava", "()Ljava/lang/String;");
}

std::string goBack() {
    LOGI("goBack");
    return callJavaNoArgMethod("goBackJava", "()Ljava/lang/String;");
}

std::string goHome() {
    LOGI("goHome");
    return callJavaNoArgMethod("goHomeJava", "()Ljava/lang/String;");
}

std::string openRecents() {
    LOGI("openRecents");
    return callJavaNoArgMethod("openRecentsJava", "()Ljava/lang/String;");
}

} // namespace argos
