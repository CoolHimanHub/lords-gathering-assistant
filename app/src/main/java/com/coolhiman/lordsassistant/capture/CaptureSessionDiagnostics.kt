package com.coolhiman.lordsassistant.capture

/**
 * Read-only roll-up of the diagnostics already produced by the live capture
 * pipeline. This class owns no safety decisions and never changes scheduler
 * eligibility or action execution.
 */
enum class CaptureSessionState {
    NO_SESSION,
    ACTIVE,
    COMPLETED
}

data class CaptureSessionDiagnosticsSnapshot(
    val capture: CaptureHealthSnapshot,
    val runtime: CaptureRuntimeSnapshot,
    val latency: ProcessingLatencySnapshot,
    val quality: CaptureQuality,
    /** Candidate rejection counts attributed to this capture session. */
    val candidateRejectionCounts: Map<String, Int> = emptyMap()
) {
    val frames: Long get() = capture.totalFrames
    val acceptedFrames: Long get() = capture.acceptedFrames
    val droppedFrames: Long get() = capture.droppedFrames
    val staleFrames: Long get() = capture.staleFrames
    val fps: Double get() = capture.framesPerSecond
    val acceptedFps: Double get() = capture.acceptedFramesPerSecond
    val dropRatePercent: Double get() = capture.dropRatePercent
    val averageProcessingMs: Double get() = capture.averageProcessingMs
    val averageOcrMs: Double get() = latency.averageOcrMs
    val averageScannerMs: Double get() = latency.averageScannerMs
    val maxProcessingMs: Long get() = latency.maxTotalMs
    val sessionId: Long get() = runtime.sessionId
    val restartCount: Long get() = runtime.restartCount
    val stallCount: Long get() = runtime.stallCount
    val viewportChangeCount: Long get() = runtime.viewportChangeCount
    val maxFrameGapMs: Long get() = capture.maxFrameGapMs
    val qualityReason: String
        get() = when {
            quality == CaptureQuality.INSUFFICIENT_DATA -> "INSUFFICIENT_FRAMES"
            capture.maxFrameGapMs >= 3000L -> "MAX_FRAME_GAP_" + capture.maxFrameGapMs + "MS"
            latency.averageTotalMs >= 1500.0 -> "PROCESSING_AVG_" + latency.averageTotalMs.toLong() + "MS"
            capture.acceptedFramesPerSecond < 0.4 -> "ACCEPTED_FPS_" + "%.2f".format(capture.acceptedFramesPerSecond)
            capture.maxFrameGapMs >= 2000L -> "FRAME_GAP_" + capture.maxFrameGapMs + "MS"
            latency.averageTotalMs >= 750.0 -> "PROCESSING_AVG_" + latency.averageTotalMs.toLong() + "MS"
            capture.acceptedFramesPerSecond < 0.8 -> "ACCEPTED_FPS_" + "%.2f".format(capture.acceptedFramesPerSecond)
            quality == CaptureQuality.HEALTHY -> "CONTINUITY_AND_THROUGHPUT_OK"
            else -> "DEGRADED"
        }
    val memorySafeForDiagnostics: Boolean
        get() = qualityReason != "MEMORY_CRITICAL"
    val sessionState: CaptureSessionState
        get() = when {
            runtime.sessionId <= 0L -> CaptureSessionState.NO_SESSION
            runtime.active -> CaptureSessionState.ACTIVE
            else -> CaptureSessionState.COMPLETED
        }
}

object CaptureSessionDiagnostics {
    fun snapshot(
        capture: CaptureHealthSnapshot,
        runtime: CaptureRuntimeSnapshot,
        latency: ProcessingLatencySnapshot,
        quality: CaptureQuality,
        candidateRejectionCounts: Map<String, Int> = emptyMap()
    ): CaptureSessionDiagnosticsSnapshot = CaptureSessionDiagnosticsSnapshot(
        capture = capture,
        runtime = runtime,
        latency = latency,
        quality = quality,
        candidateRejectionCounts = candidateRejectionCounts.toMap()
    )
}
