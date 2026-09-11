package com.coolhimanhub.lordsgatheringassistant

/**
 * V33: Tracks the map's world-coordinate header rather than relying on screen
 * pixels alone. The game exposes the current viewport as X:<n> Y:<n>; using
 * that value makes a scan globally meaningful even after the map is dragged.
 */
class MapViewportTracker {
    data class Viewport(val x: Int, val y: Int)

    private var last: Viewport? = null

    fun parse(ocrText: String): Viewport? {
        val compact = ocrText.replace('\n', ' ')
        val match = Regex("X\\s*[:=]\\s*(\\d{1,4})\\D+Y\\s*[:=]\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)
            .find(compact) ?: return null
        return Viewport(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun update(viewport: Viewport): Boolean {
        val changed = last == null || last != viewport
        last = viewport
        return changed
    }

    fun current(): Viewport? = last

    fun reset() { last = null }
}
