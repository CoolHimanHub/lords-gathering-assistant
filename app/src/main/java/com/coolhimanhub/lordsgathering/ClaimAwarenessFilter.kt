package com.coolhimanhub.lordsgathering

/**
 * Conservative claim/occupation filter for RSS targets.
 *
 * A target is NOT eligible when another player's occupation or an active
 * march/approach marker is visible. Unknown state is also rejected: AUTO
 * GATHER must prefer waiting/rescanning over taking a potentially claimed
 * node.
 */
class ClaimAwarenessFilter {
    enum class State { FREE, OCCUPIED, PLAYER_APPROACHING, UNKNOWN }

    data class Observation(
        val state: State,
        val confidence: Int = 0,
        val ownerText: String? = null,
        val approachDetected: Boolean = false
    )

    fun eligibleForAutoGather(o: Observation): Boolean {
        if (o.state != State.FREE) return false
        if (o.approachDetected) return false
        return o.confidence >= 80
    }

    fun reason(o: Observation): String = when {
        o.approachDetected -> "PLAYER_APPROACHING"
        o.state == State.OCCUPIED -> "OCCUPIED_BY_PLAYER"
        o.state == State.UNKNOWN -> "CLAIM_STATE_UNKNOWN"
        o.confidence < 80 -> "CLAIM_CONFIDENCE_LOW"
        else -> "FREE"
    }
}
