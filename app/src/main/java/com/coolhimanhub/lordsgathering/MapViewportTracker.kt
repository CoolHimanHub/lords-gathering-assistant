package com.coolhimanhub.lordsgatheringassistant

/**
 * V51: OCR-resilient viewport tracking with authoritative movement gating.
 *
 * A temporary failure to read the small X/Y HUD must not fabricate a new
 * world-coordinate observation. The last confirmed coordinate is retained as
 * a display fallback, but fallback frames are never allowed to advance the
 * coverage route or contaminate cross-viewport calibration.
 */
class MapViewportTracker {
    data class Viewport(val x: Int, val y: Int)

    private var last: Viewport? = null
    private var lastWasFallback = false

    /** Return the newest focused OCR coordinate, or a display-only fallback. */
    fun parse(ocrText: String): Viewport? {
        ViewportOcrCache.get()?.let { focused ->
            if (focused.x in 0..9999 && focused.y in 0..9999) {
                lastWasFallback = false
                return Viewport(focused.x, focused.y)
            }
        }
        last?.let {
            lastWasFallback = true
            return it
        }
        lastWasFallback = false
        return null
    }

    /** Consume the viewport; fallback frames can never confirm movement. */
    fun update(viewport: Viewport): Boolean {
        if (lastWasFallback) {
            ViewportOcrCache.clear()
            return false
        }
        val changed = last == null || last != viewport
        last = viewport
        ViewportOcrCache.clear()
        return changed
    }

    /** True when parse() reused the last confirmed X/Y. */
    fun usingFallback(): Boolean = lastWasFallback

    /** True only for a fresh focused OCR result. */
    fun hasAuthoritativeViewport(): Boolean = !lastWasFallback

    fun current(): Viewport? = last

    fun reset() {
        last = null
        lastWasFallback = false
        ViewportOcrCache.clear()
    }
}
