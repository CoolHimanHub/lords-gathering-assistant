package com.coolhimanhub.lordsgathering

/** Semantic decision produced after visual detection and before any gather action. */
data class TileDecision(
    val x: Int,
    val y: Int,
    val resourceType: String,
    val level: Int,
    val confidence: Int,
    val occupied: Boolean,
    val approaching: Boolean,
    val accepted: Boolean,
    val rejectReason: String? = null
) {
    companion object {
        fun reject(d: ScreenAnalyzer.RssDetection, reason: String) = TileDecision(
            d.centerX, d.centerY, d.type, d.level, d.confidence,
            d.occupied, d.moving, false, reason
        )
    }

    fun isSafeCandidate(): Boolean =
        accepted && resourceType != "unknown" && level in 1..5 &&
            confidence >= 68 && !occupied && !approaching
}
