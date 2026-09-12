package com.coolhimanhub.lordsgatheringassistant

/**
 * V34: Tracks the map's world-coordinate header rather than relying on screen
 * pixels alone. Focused viewport OCR is preferred; legacy OCR text remains a
 * fallback for compatibility.
 */
class MapViewportTracker {
    data class Viewport(val x: Int, val y: Int)

    private var last: Viewport? = null

    fun parse(ocrText: String): Viewport? {
        // V34 focused crop result is authoritative for the screenshot that was
        // just analyzed. It is populated before the service's legacy OCR
        // callback invokes this parser.
        ViewportOcrCache.get()?.let { focused ->
            return Viewport(focused.x, focused.y)
        }

        val compact = ocrText
            .replace('\n', ' ')
            .replace('|', 'I')
            .replace('—', '-')
            .replace('–', '-')

        val patterns = listOf(
            Regex("X\\s*[:=]\\s*(\\d{1,4})\\D+Y\\s*[:=]\\s*(\\d{1,4})", RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*(\\d{1,4})\\D+Y\\s*(\\d{1,4})", RegexOption.IGNORE_CASE),
            Regex("(?i)\\bK\\s*[:=]\\s*(\\d{1,4})\\D+Y\\s*[:=]\\s*(\\d{1,4})")
        )

        for (pattern in patterns) {
            val match = pattern.find(compact) ?: continue
            return Viewport(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        return null
    }

    fun update(viewport: Viewport): Boolean {
        val changed = last == null || last != viewport
        last = viewport
        // Do not let a stale focused result leak into a later screenshot.
        ViewportOcrCache.clear()
        return changed
    }

    fun current(): Viewport? = last

    fun reset() {
        last = null
        ViewportOcrCache.clear()
    }
}
