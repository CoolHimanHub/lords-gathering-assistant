package com.coolhiman.lordsassistant.vision

import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * Owns OpenCV's process-wide native library initialization.
 *
 * The Maven OpenCV artifact ships the native library, but Android does not
 * guarantee that the JNI library is usable before the first Mat constructor.
 * Keep loading explicit and perform a JNI smoke test before reporting success.
 * Vision callers fail closed when the native binding is unavailable.
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

            // A successful dlopen is not sufficient: the Java binding can still
            // be present without the JNI implementation for Mat. Exercise the
            // exact native entry point used by every detector before allowing
            // vision code to continue.
            val probe = Mat()
            probe.release()

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

    fun isLoaded(): Boolean = loaded

    fun error(): String? = loadError
}
