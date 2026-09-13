package com.coolhimanhub.lordsgatheringassistant

/** V54: OCR bridge that preserves the last confirmed coordinate as fallback. */
object ViewportOcrCache {
    @Volatile private var latest: ViewportOcrReader.Result? = null
    @Volatile private var lastConfirmed: ViewportOcrReader.Result? = null

    fun set(result:ViewportOcrReader.Result?) {
        latest=result
        if(result!=null) lastConfirmed=result
    }

    fun get():ViewportOcrReader.Result? = latest ?: lastConfirmed ?: ViewportOcrReader.Result(0,0)

    fun clear() {
        latest=null
    }

    fun reset() {
        latest=null
        lastConfirmed=null
    }
}
