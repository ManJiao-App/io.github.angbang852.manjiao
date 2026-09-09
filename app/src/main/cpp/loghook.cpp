#include <jni.h>
#include <stdarg.h>
#include <string.h>
#include <android/log.h>
#include "dobby.h"

static int (*orig_buf_write)(int, int, const char*, const char*) = nullptr;
static int (*orig_log_write)(int, const char*, const char*) = nullptr;
static int (*orig_log_vprint)(int, const char*, const char*, va_list) = nullptr;


static bool is_spam_tag(const char* tag) {
    if (!tag) return false;
    return strcmp(tag, "xySDK") == 0 || strcmp(tag, "Klink") == 0;
}

static bool is_spam_text(const char* text) {
    if (!text) return false;
    return strstr(text, "Invalid resource ID") != nullptr;
}

static int hook_buf_write(int bufID, int prio, const char* tag, const char* text) {
    if (is_spam_tag(tag) || is_spam_text(text)) return 0;
    return orig_buf_write(bufID, prio, tag, text);
}

static int hook_log_write(int prio, const char* tag, const char* text) {
    if (is_spam_tag(tag) || is_spam_text(text)) return 0;
    return orig_log_write(prio, tag, text);
}

static int hook_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
    if (is_spam_tag(tag) || is_spam_text(fmt)) return 0;
    return orig_log_vprint(prio, tag, fmt, ap);
}


extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_angbang852_manjiao_hook_PerfHook_nativeInitLogHook(JNIEnv*, jclass) {
    int ok = 0;
    void* p1 = (void*)&__android_log_buf_write;
    if (p1 && DobbyHook(p1, (void*)hook_buf_write, (void**)&orig_buf_write) == 0) ok++;
    void* p2 = (void*)&__android_log_write;
    if (p2 && DobbyHook(p2, (void*)hook_log_write, (void**)&orig_log_write) == 0) ok++;
    void* p3 = (void*)&__android_log_vprint;
    if (p3 && DobbyHook(p3, (void*)hook_log_vprint, (void**)&orig_log_vprint) == 0) ok++;

    __android_log_print(ANDROID_LOG_INFO, "SlowKick", "native loghook init ok=%d", ok);
    return ok > 0 ? JNI_TRUE : JNI_FALSE;
}
