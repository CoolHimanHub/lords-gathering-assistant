package com.coolhimanhub.lordsgatheringassistant

/** V54: OCR bridge with a non-blocking bootstrap coordinate. */
object ViewportOcrCache {
    @Volatile private var latest: ViewportOcrReader.Result? = null

    fun set(result:ViewportOcrReader.Result?) {
        latest=result
    }

    fun get():ViewportOcrReader.Result? = latest ?: ViewportOcrReader.Result(0,0)

    fun clear() {
        latest=null
    }
}
