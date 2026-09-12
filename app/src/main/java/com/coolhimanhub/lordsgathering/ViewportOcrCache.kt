package com.coolhimanhub.lordsgatheringassistant

/**
 * V34 bridge between screenshot analysis and the existing service OCR callback.
 * A value is written only after focused coordinate OCR succeeds for the current
 * screenshot. It is consumed by MapViewportTracker before falling back to the
 * legacy full-frame OCR text.
 */
object ViewportOcrCache {
    @Volatile private var latest: ViewportOcrReader.Result? = null

    fun set(result: ViewportOcrReader.Result?) {
        latest = result
    }

    fun get(): ViewportOcrReader.Result? = latest

    fun clear() {
        latest = null
    }
}
