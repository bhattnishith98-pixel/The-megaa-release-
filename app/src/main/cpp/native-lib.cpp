#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_runtime_NativeRuntimeBridge_nativeBuildInfo(JNIEnv *env, jobject /* thiz */) {
    const std::string info =
        "Automation Studio native runtime: C++20 / Android NDK / CMake";
    return env->NewStringUTF(info.c_str());
}
