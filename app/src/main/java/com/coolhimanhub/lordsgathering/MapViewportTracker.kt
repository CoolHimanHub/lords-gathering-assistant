package com.coolhimanhub.lordsgatheringassistant

/**
 * V43: OCR-resilient viewport tracking.
 *
 * A temporary failure to read the small X/Y HUD must not stop the coverage
 * scanner. The last authoritative coordinate is retained as a fallback until
 * a fresh focused OCR result arrives. The fallback is explicitly marked so
 * callers can avoid treating it as a new world-coordinate observation.
 */
class MapViewportTracker {
    data class Viewport(val x: Int, val y: Int)

    private var last: Viewport? = null
    private var lastWasFallback = false

    /**
     * Return the newest focused OCR coordinate. If OCR is temporarily blocked
     * by labels/numbers on the game map, return the last confirmed coordinate
     * instead of returning null and killing the sweep state machine.
     */
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

    fun update(viewport: Viewport): Boolean {
        val changed = last == null || last != viewport
        last = viewport
        // Clear immediately after consuming the focused result so it cannot be
        // reused by a later screenshot.
        ViewportOcrCache.clear()
        return changed
    }

    /** True when parse() had to reuse the last confirmed X/Y. */
    fun usingFallback(): Boolean = lastWasFallback

    fun current(): Viewport? = last

    fun reset() {
        last = null
        lastWasFallback = false
        ViewportOcrCache.clear()
    }
}
