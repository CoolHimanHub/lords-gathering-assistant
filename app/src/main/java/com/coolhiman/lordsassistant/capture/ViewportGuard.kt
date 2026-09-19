package com.coolhiman.lordsassistant.capture

/**
 * Ensures screen-coordinate calibration is not silently reused across
 * different capture dimensions during one capture session.
 */
class ViewportGuard {
    private var width: Int? = null
    private var height: Int? = null

    fun accept(currentWidth: Int, currentHeight: Int): Boolean {
        if (currentWidth <= 0 || currentHeight <= 0) return false
        if (width == null || height == null) {
            width = currentWidth
            height = currentHeight
            return true
        }
        return width == currentWidth && height == currentHeight
    }

    fun reset() {
        width = null
        height = null
    }

    fun dimensions(): Pair<Int, Int>? =
        if (width != null && height != null) width!! to height!! else null
}
