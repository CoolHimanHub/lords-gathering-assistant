package com.coolhimanhub.lordsgatheringassistant

/**
 * V36: Tracks the map's world-coordinate header from the focused OCR result
 * produced from the exact screenshot being analyzed.
 *
 * Deliberately does NOT fall back to full-screen OCR. Full-screen OCR can pick
 * unrelated numbers from chat, timers, player names, or resource badges and
 * would break the association between a candidate set and its viewport.
 */
class MapViewportTracker {
    data class Viewport(val x: Int, val y: Int)

    private var last: Viewport? = null

    /**
     * The focused result is the only authoritative viewport source. The OCR
     * text argument is retained so the service call site remains unchanged,
     * but it is intentionally ignored.
     */
    fun parse(ocrText: String): Viewport? {
        ViewportOcrCache.get()?.let { focused ->
            if (focused.x in 0..9999 && focused.y in 0..9999) {
                return Viewport(focused.x, focused.y)
            }
        }
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

    fun current(): Viewport? = last

    fun reset() {
        last = null
        ViewportOcrCache.clear()
    }
}
