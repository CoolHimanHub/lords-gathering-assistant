package com.coolhimanhub.lordsgatheringassistant

/** V54: OCR bridge with an explicit authoritative/fallback distinction. */
object ViewportOcrCache {
    @Volatile private var latest: ViewportOcrReader.Result? = null
    @Volatile private var lastConfirmed: ViewportOcrReader.Result? = null

    fun set(result:ViewportOcrReader.Result?) {
        latest=result
        if(result!=null) lastConfirmed=result
    }

    /** Latest OCR result, or the last confirmed result when OCR missed this frame. */
    fun get():ViewportOcrReader.Result? = latest ?: lastConfirmed

    fun clear() {
        latest=null
    }

    fun reset() {
        latest=null
        lastConfirmed=null
    }
}
