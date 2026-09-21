package com.coolhiman.lordsassistant.capture

/**
 * Lightweight, allocation-free capture-session telemetry.
 *
 * This class deliberately contains no Android dependencies so the timing and
 * drop accounting can be unit-tested deterministically. It is diagnostic only:
 * it never relaxes any safety gate.
 */
data class CaptureHealthSnapshot(
    val sessionStarted: Boolean,
    val totalFrames: Long,
    val acceptedFrames: Long,
    val droppedFrames: Long,
    val staleFrames: Long,
    val processedFrames: Long,
    val viewportResets: Long,
    val lastFrameGapMs: Long?,
    val maxFrameGapMs: Long,
    val lastProcessingMs: Long?,
    val maxProcessingMs: Long,
    val averageProcessingMs: Double,
    val lastFrameAtMs: Long?
) {
    val dropRatePercent: Double
        get() = if (totalFrames == 0L) 0.0 else droppedFrames * 100.0 / totalFrames
}

class CaptureHealthTracker {
    private var started = false
    private var totalFrames = 0L
    private var acceptedFrames = 0L
    private var droppedFrames = 0L
    private var staleFrames = 0L
    private var processedFrames = 0L
    private var viewportResets = 0L
    private var lastFrameAtMs: Long? = null
    private var lastFrameGapMs: Long? = null
    private var maxFrameGapMs = 0L
    private var lastProcessingMs: Long? = null
    private var maxProcessingMs = 0L
    private var processingTotalMs = 0L

    fun start(nowMs: Long) {
        started = true
        lastFrameAtMs = nowMs
    }

    fun stop() {
        started = false
    }

    fun frameArrived(nowMs: Long): Long {
        if (!started) start(nowMs)
        totalFrames++
        val previous = lastFrameAtMs
        if (previous != null && nowMs >= previous) {
            val gap = nowMs - previous
            lastFrameGapMs = gap
            if (gap > maxFrameGapMs) maxFrameGapMs = gap
        }
        lastFrameAtMs = nowMs
        return totalFrames
    }

    fun frameAccepted() {
        acceptedFrames++
    }

    fun frameDropped(stale: Boolean = false) {
        droppedFrames++
        if (stale) staleFrames++
    }

    fun processingFinished(processingMs: Long) {
        val safe = processingMs.coerceAtLeast(0L)
        processedFrames++
        lastProcessingMs = safe
        if (safe > maxProcessingMs) maxProcessingMs = safe
        processingTotalMs += safe
    }

    fun viewportReset() {
        viewportResets++
    }

    fun snapshot(): CaptureHealthSnapshot = CaptureHealthSnapshot(
        sessionStarted = started,
        totalFrames = totalFrames,
        acceptedFrames = acceptedFrames,
        droppedFrames = droppedFrames,
        staleFrames = staleFrames,
        processedFrames = processedFrames,
        viewportResets = viewportResets,
        lastFrameGapMs = lastFrameGapMs,
        maxFrameGapMs = maxFrameGapMs,
        lastProcessingMs = lastProcessingMs,
        maxProcessingMs = maxProcessingMs,
        averageProcessingMs = if (processedFrames == 0L) 0.0 else processingTotalMs.toDouble() / processedFrames,
        lastFrameAtMs = lastFrameAtMs
    )

    fun reset() {
        started = false
        totalFrames = 0L
        acceptedFrames = 0L
        droppedFrames = 0L
        staleFrames = 0L
        processedFrames = 0L
        viewportResets = 0L
        lastFrameAtMs = null
        lastFrameGapMs = null
        maxFrameGapMs = 0L
        lastProcessingMs = null
        maxProcessingMs = 0L
        processingTotalMs = 0L
    }
}


/**
 * Detects a live-capture stall without ever relaxing action safety.
 *
 * A frame arrival clears the stalled condition. Once the session is active,
 * exceeding the timeout produces one edge-triggered stall signal so the
 * capture service can fail closed and stop the session.
 */
class CaptureWatchdog(
    private val stallTimeoutMs: Long = 3000L
) {
    init {
        require(stallTimeoutMs > 0L)
    }

    private var active = false
    private var lastFrameAtMs: Long? = null
    private var stalled = false

    fun start(nowMs: Long) {
        active = true
        lastFrameAtMs = nowMs
        stalled = false
    }

    fun frameArrived(nowMs: Long) {
        if (!active) return
        lastFrameAtMs = nowMs
        stalled = false
    }

    fun check(nowMs: Long): Boolean {
        if (!active || stalled) return false
        val last = lastFrameAtMs ?: return false
        if (nowMs < last || nowMs - last < stallTimeoutMs) return false
        stalled = true
        return true
    }

    fun stop() {
        active = false
        lastFrameAtMs = null
        stalled = false
    }

    fun isActive(): Boolean = active
    fun isStalled(): Boolean = stalled
}
