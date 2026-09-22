package com.coolhiman.lordsassistant.vision

import org.opencv.core.Core

/**
 * Owns OpenCV's process-wide native library initialization.
 *
 * The Maven OpenCV artifact ships the native library, but Android does not
 * guarantee that the JNI library is loaded before the first Mat constructor.
 * Keep loading explicit and fail closed so a vision feature can never crash
 * the capture service.
 */
object OpenCvRuntime {
    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false

        return try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
            loaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            loadError = (error.message ?: error.javaClass.simpleName).take(180)
            false
        } catch (error: Throwable) {
            loadError = (error.message ?: error.javaClass.simpleName).take(180)
            false
        }
    }

    fun error(): String? = loadError
}
