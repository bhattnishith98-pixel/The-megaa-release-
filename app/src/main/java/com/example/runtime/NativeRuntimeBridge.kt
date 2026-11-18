package com.example.runtime

/**
 * Small JNI bridge used to verify that the app's C/C++ sources were compiled
 * by the Android NDK and packaged into the APK for the current ABI.
 * This is separate from the optional on-device Clang ZIP installer.
 */
object NativeRuntimeBridge {
    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("automation_studio_native")
        true
    }.getOrDefault(false)

    private external fun nativeBuildInfo(): String

    fun isAvailable(): Boolean = libraryLoaded

    fun buildInfo(): String = if (libraryLoaded) {
        runCatching { nativeBuildInfo() }.getOrElse { "Native library loaded, JNI check failed: ${it.message}" }
    } else {
        "Native library is not packaged for this device ABI."
    }
}
