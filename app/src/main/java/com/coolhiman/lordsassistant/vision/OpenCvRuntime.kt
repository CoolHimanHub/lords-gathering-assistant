package com.coolhiman.lordsassistant.vision

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * Owns OpenCV's process-wide native library initialization.
 *
 * The Maven OpenCV AAR packages the Android native library. Prefer the direct
 * loader used by the OpenCV Android SDK, then fall back to the loader path
 * documented by OpenCV. A successful library load is followed by a Mat JNI
 * smoke test before vision code is allowed to run.
 */
object OpenCvRuntime {
    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true

        var directError: String? = null
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            if (jniProbe()) {
                loaded = true
                loadError = null
                return true
            }
            directError = "System.loadLibrary succeeded but Mat JNI probe failed"
        } catch (error: Throwable) {
            directError = formatError(error)
        }

        // Some Android packaging/class-loader combinations expose the native
        // library through OpenCVLoader even when direct System.loadLibrary()
        // cannot resolve it. Do not cache the first failure before this path.
        return try {
            if (OpenCVLoader.initDebug() && jniProbe()) {
                loaded = true
                loadError = null
                true
            } else {
                loadError = listOfNotNull(
                    "direct=$directError",
                    "OpenCVLoader.initDebug() returned false or JNI probe failed"
                ).joinToString(" | ").take(240)
                false
            }
        } catch (error: Throwable) {
            loadError = listOfNotNull(
                "direct=$directError",
                "loader=${formatError(error)}"
            ).joinToString(" | ").take(240)
            false
        }
    }

    private fun jniProbe(): Boolean {
        val probe = Mat()
        return try {
            !probe.empty()
        } finally {
            probe.release()
        }
    }

    private fun formatError(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(180)

    fun isLoaded(): Boolean = loaded

    fun error(): String? = loadError

    fun diagnostic(): String = when {
        loaded -> "READY"
        loadError != null -> "UNAVAILABLE: ${loadError}"
        else -> "NOT_INITIALIZED"
    }
}
