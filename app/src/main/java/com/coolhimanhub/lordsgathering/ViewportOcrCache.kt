package com.coolhimanhub.lordsgatheringassistant

/**
 * V54 bridge between screenshot analysis and viewport tracking.
 *
 * A missing OCR result must not stop the scan loop. A 0/0 bootstrap value is
 * intentionally used only until the first real coordinate arrives; the grid
 * mapper rejects the resulting large jump as a calibration pair.
 */
object ViewportOcrCache {
    @Volatile private var latest: ViewportOcrReader.Result? = null

    fun set(result:ViewportOcrReader.Result?) {
        if(result!=null) latest=result
    }

    fun get():ViewportOcrReader.Result? = latest ?: ViewportOcrReader.Result(0,0)

    fun clear() { }
}
