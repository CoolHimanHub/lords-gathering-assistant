package com.coolhiman.lordsassistant.capture

/**
 * Deterministic lifecycle telemetry for one or more screen-capture sessions.
 *
 * This is diagnostic state only. It never authorizes actions or relaxes a
 * fail-closed capture/safety condition.
 */
enum class CaptureStopReason {
    USER_STOP,
    PROJECTION_STOPPED,
    CAPTURE_STALLED,
    VIEWPORT_CHANGED,
    CAPTURE_SETUP_FAILED,
    CAPTURE_ERROR,
    SERVICE_DESTROYED
}

data class CaptureRuntimeSnapshot(
    val sessionId: Long,
    val active: Boolean,
    val startCount: Long,
    val restartCount: Long,
    val stallCount: Long,
    val viewportChangeCount: Long,
    val lastStopReason: CaptureStopReason?,
    val lastFailureReason: String?
)

class CaptureRuntimeSessionTracker {
    private var sessionId = 0L
    private var active = false
    private var startCount = 0L
    private var restartCount = 0L
    private var stallCount = 0L
    private var viewportChangeCount = 0L
    private var lastStopReason: CaptureStopReason? = null
    private var lastFailureReason: String? = null

    fun start() {
        if (active) return
        if (startCount > 0L) restartCount++
        startCount++
        sessionId++
        active = true
        lastStopReason = null
        lastFailureReason = null
    }

    fun recordStall() {
        stallCount++
        lastFailureReason = CaptureStopReason.CAPTURE_STALLED.name
    }

    fun recordViewportChange() {
        viewportChangeCount++
        lastFailureReason = CaptureStopReason.VIEWPORT_CHANGED.name
    }

    fun recordFailure(reason: String) {
        lastFailureReason = reason.take(160)
    }

    fun stop(reason: CaptureStopReason) {
        active = false
        lastStopReason = reason
    }

    fun snapshot(): CaptureRuntimeSnapshot = CaptureRuntimeSnapshot(
        sessionId = sessionId,
        active = active,
        startCount = startCount,
        restartCount = restartCount,
        stallCount = stallCount,
        viewportChangeCount = viewportChangeCount,
        lastStopReason = lastStopReason,
        lastFailureReason = lastFailureReason
    )

    fun reset() {
        sessionId = 0L
        active = false
        startCount = 0L
        restartCount = 0L
        stallCount = 0L
        viewportChangeCount = 0L
        lastStopReason = null
        lastFailureReason = null
    }
}
