#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>

#include "agent_client_core.h"
#include "platform.h"
#include "whisper_wrapper.h"

#define TAG "Argos"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static AgentClientCore g_agent;
static JavaVM* g_jvm = nullptr;
static jobject g_service = nullptr;

// Helper: call Java method on UI thread
static void callJavaMethod(const char* methodName, const char* sig, const std::string& arg) {
    if (!g_jvm || !g_service) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return;

    jclass cls = env->GetObjectClass(g_service);
    if (cls) {
        jmethodID mid = env->GetMethodID(cls, methodName, sig);
        if (mid) {
            if (sig[0] == '(' && sig[1] == 'L') {
                // String argument
                jstring jstr = env->NewStringUTF(arg.c_str());
                env->CallVoidMethod(g_service, mid, jstr);
                env->DeleteLocalRef(jstr);
            } else {
                env->CallVoidMethod(g_service, mid);
            }
        }
        env->DeleteLocalRef(cls);
    }

    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeInit(JNIEnv* env, jobject service, jfloat screenWidth, jfloat screenHeight) {
    LOGI("nativeInit screen=%.0fx%.0f (WebView/Three.js mode)", screenWidth, screenHeight);

    env->GetJavaVM(&g_jvm);
    if (g_service) {
        env->DeleteGlobalRef(g_service);
    }
    g_service = env->NewGlobalRef(service);

    // Set app data dir
    argos::setAppDataDir("/data/data/com.example.argos/files");

    // Set JNI for HTTP requests (uses Java HttpURLConnection for HTTPS support)
    argos::setJniForHttp(g_jvm, g_service);
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeSendChat(JNIEnv* env, jobject service, jstring message) {
    const char* msg = env->GetStringUTFChars(message, nullptr);
    std::string userMsg(msg);
    env->ReleaseStringUTFChars(message, msg);

    LOGI("nativeSendChat: %s", userMsg.c_str());

    // Get current app context from Java (proactive screen awareness)
    std::string currentAppContext;
    {
        jclass cls = env->GetObjectClass(service);
        jmethodID mid = env->GetMethodID(cls, "getCurrentAppContext", "()Ljava/lang/String;");
        if (mid) {
            jstring jresult = (jstring) env->CallObjectMethod(service, mid);
            if (jresult) {
                const char* chars = env->GetStringUTFChars(jresult, nullptr);
                if (chars) {
                    currentAppContext = chars;
                    env->ReleaseStringUTFChars(jresult, chars);
                }
                env->DeleteLocalRef(jresult);
            }
        }
        env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // Prepend current app context to the message
    if (!currentAppContext.empty()) {
        userMsg = "[Context: User is currently using " + currentAppContext + "]\n" + userMsg;
    }

    // Run chat in background thread
    std::thread([userMsg]() {
        LOGI("Chat thread started for message: %s", userMsg.c_str());

        auto startTime = std::chrono::high_resolution_clock::now();

        std::string accumulated;
        std::string response = g_agent.chatStreaming(userMsg,
            [&](const std::string& delta) -> bool {
                // Check for reset signal (sent before follow-up iterations)
                if (delta == "\x01RESET\x01") {
                    accumulated.clear();
                    return !g_agent.m_abort.load();
                }
                accumulated += delta;
                // Strip [TOOL:...] tags from displayed text
                std::string display = accumulated;
                size_t pos = 0;
                while ((pos = display.find("[TOOL:", pos)) != std::string::npos) {
                    size_t end = display.find(']', pos);
                    if (end == std::string::npos) break;
                    display.erase(pos, end - pos + 1);
                }
                // Clean up whitespace
                auto cleanStart = display.find_first_not_of(" \t\n\r");
                if (cleanStart == std::string::npos) display = "";
                else if (cleanStart > 0) display = display.substr(cleanStart);
                if (!display.empty()) {
                    callJavaMethod("onChatStream", "(Ljava/lang/String;)V", display);
                }
                return !g_agent.m_abort.load();
            },
            [&](const std::string& thoughts) {
                callJavaMethod("onChatThoughts", "(Ljava/lang/String;)V", thoughts);
            },
            [&](const std::string& status) {
                callJavaMethod("onToolStatus", "(Ljava/lang/String;)V", status);
            });

        auto endTime = std::chrono::high_resolution_clock::now();
        auto durationMs = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();

        LOGI("Chat response: %s (len=%zu, time=%lldms)", response.c_str(), response.size(), (long long)durationMs);

        // Send metrics to Java
        char metricsBuf[128];
        snprintf(metricsBuf, sizeof(metricsBuf), "%lldms|%zuchars", (long long)durationMs, response.size());
        callJavaMethod("onChatMetrics", "(Ljava/lang/String;)V", metricsBuf);

        if (g_agent.m_abort.load()) {
            callJavaMethod("onChatError", "(Ljava/lang/String;)V", "Cancelled");
        } else if (response.empty()) {
            callJavaMethod("onChatError", "(Ljava/lang/String;)V", "No response from server. Check network connection.");
        } else if (response.find("[Error:") != std::string::npos) {
            callJavaMethod("onChatError", "(Ljava/lang/String;)V", response);
        } else {
            callJavaMethod("onChatResponse", "(Ljava/lang/String;)V", response);
            // Auto-TTS: speak the response aloud (like desktop)
            std::string cleanResponse = response;
            // Strip [TOOL:...] tags
            size_t pos = 0;
            while ((pos = cleanResponse.find("[TOOL:", pos)) != std::string::npos) {
                size_t end = cleanResponse.find(']', pos);
                if (end == std::string::npos) break;
                cleanResponse.erase(pos, end - pos + 1);
            }
            // Strip [Tool result:...] blocks
            pos = 0;
            while ((pos = cleanResponse.find("[Tool ", pos)) != std::string::npos) {
                size_t end = cleanResponse.find(']', pos);
                if (end == std::string::npos) break;
                cleanResponse.erase(pos, end - pos + 1);
            }
            // Trim whitespace
            auto start = cleanResponse.find_first_not_of(" \t\n\r");
            if (start != std::string::npos) {
                cleanResponse = cleanResponse.substr(start);
            }
            if (!cleanResponse.empty()) {
                argos::ttsSpeak(cleanResponse);
            }
        }
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeResume(JNIEnv* env, jobject service) {
    LOGI("nativeResume (WebView mode — no-op)");
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativePause(JNIEnv* env, jobject service) {
    LOGI("nativePause (WebView mode — no-op)");
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeDestroy(JNIEnv* env, jobject service) {
    LOGI("nativeDestroy");
    g_agent.m_abort.store(true);
    if (g_service) {
        env->DeleteGlobalRef(g_service);
        g_service = nullptr;
    }
}

// ── AI Robot Control JNI (called from C++ tool execution) ──

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeRobotExpression(JNIEnv* env, jobject service, jstring expression) {
    const char* expr = env->GetStringUTFChars(expression, nullptr);
    callJavaMethod("robotSetExpression", "(Ljava/lang/String;)V", expr);
    env->ReleaseStringUTFChars(expression, expr);
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeRobotMoveTo(JNIEnv* env, jobject service, jfloat x, jfloat y) {
    if (!g_jvm || !g_service) return;
    JNIEnv* jenv = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&jenv, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&jenv, nullptr);
        attached = true;
    }
    if (!jenv) return;
    jclass cls = jenv->GetObjectClass(g_service);
    if (cls) {
        jmethodID mid = jenv->GetMethodID(cls, "robotMoveTo", "(FF)V");
        if (mid) jenv->CallVoidMethod(g_service, mid, x, y);
        jenv->DeleteLocalRef(cls);
    }
    if (attached) g_jvm->DetachCurrentThread();
}

JNIEXPORT void JNICALL
Java_com_argos_companion_FloatingRobotService_nativeRobotSetState(JNIEnv* env, jobject service, jstring state) {
    const char* s = env->GetStringUTFChars(state, nullptr);
    callJavaMethod("robotSetState", "(Ljava/lang/String;)V", s);
    env->ReleaseStringUTFChars(state, s);
}

JNIEXPORT jstring JNICALL
Java_com_argos_companion_FloatingRobotService_nativeRobotGetPosition(JNIEnv* env, jobject service) {
    if (!g_jvm || !g_service) return env->NewStringUTF("{}");
    JNIEnv* jenv = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&jenv, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&jenv, nullptr);
        attached = true;
    }
    if (!jenv) return env->NewStringUTF("{}");
    std::string result = "{}";
    jclass cls = jenv->GetObjectClass(g_service);
    if (cls) {
        jmethodID mid = jenv->GetMethodID(cls, "robotGetPosition", "()Ljava/lang/String;");
        if (mid) {
            jstring jr = (jstring) jenv->CallObjectMethod(g_service, mid);
            if (jr) {
                const char* chars = jenv->GetStringUTFChars(jr, nullptr);
                if (chars) { result = chars; jenv->ReleaseStringUTFChars(jr, chars); }
                jenv->DeleteLocalRef(jr);
            }
        }
        jenv->DeleteLocalRef(cls);
    }
    if (attached) g_jvm->DetachCurrentThread();
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
