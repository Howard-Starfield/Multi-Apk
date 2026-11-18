#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>
#include <sys/system_properties.h>
#include <cstring>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Per-process override table, empty by default (real values pass through).
// Replaced wholesale (copy-on-write) by setSpoofProps; readers take a shared_ptr snapshot.
typedef std::vector<std::pair<std::string, std::string>> SpoofTable;
static std::shared_ptr<const SpoofTable> g_spoof_table;
static std::mutex g_spoof_mutex;

static std::shared_ptr<const SpoofTable> get_spoof_table() {
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    return g_spoof_table;
}

static void install_property_get_hook();
static std::once_flag g_hook_once;

void set_spoof_props(JNIEnv *env, jclass clazz, jobjectArray keys, jobjectArray values) {
    auto table = std::make_shared<SpoofTable>();
    if (keys != nullptr && values != nullptr) {
        jsize n = env->GetArrayLength(keys);
        if (env->GetArrayLength(values) < n) n = env->GetArrayLength(values);
        for (jsize i = 0; i < n; ++i) {
            jstring k = (jstring) env->GetObjectArrayElement(keys, i);
            jstring v = (jstring) env->GetObjectArrayElement(values, i);
            if (k != nullptr && v != nullptr) {
                const char *kc = env->GetStringUTFChars(k, nullptr);
                const char *vc = env->GetStringUTFChars(v, nullptr);
                if (kc != nullptr && vc != nullptr) {
                    table->emplace_back(kc, vc);
                }
                if (kc != nullptr) env->ReleaseStringUTFChars(k, kc);
                if (vc != nullptr) env->ReleaseStringUTFChars(v, vc);
            }
            if (k != nullptr) env->DeleteLocalRef(k);
            if (v != nullptr) env->DeleteLocalRef(v);
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_spoof_mutex);
        g_spoof_table = table;
    }
    // The libc hook is installed only once a clone actually overrides a property. Patching libc
    // unconditionally made app packers that check libc integrity (360 Jiagu) refuse to unpack and
    // exit, leaving those clones on a black screen.
    if (!table->empty()) {
        std::call_once(g_hook_once, install_property_get_hook);
    }
}

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;


int my_system_property_get(const char *name, char *value) {
    if (name != nullptr) {
        std::shared_ptr<const SpoofTable> table = get_spoof_table();
        if (table) {
            for (const auto &entry : *table) {
                if (entry.first == name) {
                    // PROP_VALUE_MAX is 92 including the terminating NUL.
                    strncpy(value, entry.second.c_str(), PROP_VALUE_MAX - 1);
                    value[PROP_VALUE_MAX - 1] = 0;
                    return (int) strlen(value);
                }
            }
        }
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = 0;
    return 0;
}

static void install_property_get_hook() {
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target) {
        if (DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get) == 0) {
            LOGD("Spoof installed successfully");
        } else {
            LOGD("Spoof hook failed");
        }
        xdl_close(handle);
    } else{
        xdl_close(handle);
    }

}

