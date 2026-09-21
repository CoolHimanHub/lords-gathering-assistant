package com.coolhiman.lordsassistant.capture

enum class CaptureQuality {
    INSUFFICIENT_DATA,
    HEALTHY,
    DEGRADED,
    UNSAFE
}

/**
 * Deterministic diagnostic interpretation of capture telemetry.
 *
 * This policy is intentionally advisory: it never authorizes or blocks an
 * action directly. Existing camera continuity, validation, scheduler and
 * execution gates remain the only action-safety boundary.
 */
class CaptureQualityPolicy(
    private val minimumFrames: Long = 5L,
    private val healthyFps: Double = 1.2,
    private val degradedFps: Double = 0.8,
    private val degradedDropRatePercent: Double = 20.0,
    private val unsafeDropRatePercent: Double = 50.0,
    private val degradedProcessingMs: Double = 750.0,
    private val unsafeProcessingMs: Double = 1500.0,
    private val degradedGapMs: Long = 2000L,
    private val unsafeGapMs: Long = 3000L
) {
    init {
        require(minimumFrames > 0L)
        require(healthyFps > 0.0)
        require(degradedFps > 0.0 && degradedFps < healthyFps)
        require(degradedDropRatePercent in 0.0..100.0)
        require(unsafeDropRatePercent >= degradedDropRatePercent && unsafeDropRatePercent <= 100.0)
        require(degradedProcessingMs > 0.0 && unsafeProcessingMs >= degradedProcessingMs)
        require(degradedGapMs > 0L && unsafeGapMs >= degradedGapMs)
    }

    fun assess(
        capture: CaptureHealthSnapshot,
        latency: ProcessingLatencySnapshot,
        memory: MemoryPressureSnapshot
    ): CaptureQuality {
        if (capture.totalFrames < minimumFrames) return CaptureQuality.INSUFFICIENT_DATA
        if (memory.level == MemoryPressureLevel.CRITICAL) return CaptureQuality.UNSAFE
        if (
            capture.dropRatePercent >= unsafeDropRatePercent ||
            capture.maxFrameGapMs >= unsafeGapMs ||
            latency.averageTotalMs >= unsafeProcessingMs
        ) return CaptureQuality.UNSAFE

        if (
            memory.level == MemoryPressureLevel.WARNING ||
            capture.framesPerSecond < degradedFps ||
            capture.dropRatePercent >= degradedDropRatePercent ||
            capture.maxFrameGapMs >= degradedGapMs ||
            latency.averageTotalMs >= degradedProcessingMs
        ) return CaptureQuality.DEGRADED

        return if (capture.framesPerSecond >= healthyFps) {
            CaptureQuality.HEALTHY
        } else {
            CaptureQuality.DEGRADED
        }
    }
}
