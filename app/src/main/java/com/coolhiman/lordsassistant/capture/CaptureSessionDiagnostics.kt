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
    val memorySafeForDiagnostics: Boolean get() = quality != CaptureQuality.UNSAFE
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
