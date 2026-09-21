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

    fun frameDropped() {
        droppedFrames++
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
