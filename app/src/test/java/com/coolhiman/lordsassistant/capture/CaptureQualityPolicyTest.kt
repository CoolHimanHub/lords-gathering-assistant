package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureQualityPolicyTest {
    private val policy = CaptureQualityPolicy()

    private fun capture(
        frames: Long = 10L,
        durationMs: Long = 5000L,
        drops: Long = 0L,
        maxGapMs: Long = 1000L
    ) = CaptureHealthSnapshot(
        sessionStarted = true,
        totalFrames = frames,
        acceptedFrames = frames - drops,
        droppedFrames = drops,
        staleFrames = 0L,
        processedFrames = frames,
        viewportResets = 0L,
        lastFrameGapMs = maxGapMs,
        maxFrameGapMs = maxGapMs,
        lastProcessingMs = 100L,
        maxProcessingMs = 100L,
        averageProcessingMs = 100.0,
        lastFrameAtMs = durationMs,
        sessionDurationMs = durationMs
    )

    private fun latency(totalMs: Double) = ProcessingLatencySnapshot(
        frames = 10L,
        lastOcrMs = 50L,
        lastScannerMs = 50L,
        lastTotalMs = totalMs.toLong(),
        maxOcrMs = 50L,
        maxScannerMs = 50L,
        maxTotalMs = totalMs.toLong(),
        averageOcrMs = 50.0,
        averageScannerMs = 50.0,
        averageTotalMs = totalMs
    )

    private fun memory(level: MemoryPressureLevel) = MemoryPressureSnapshot(
        usedBytes = 80L,
        maxBytes = 100L,
        usedRatio = 0.8,
        level = level
    )

    @Test
    fun insufficientFramesRemainUnclassified() {
        assertEquals(
            CaptureQuality.INSUFFICIENT_DATA,
            policy.assess(capture(frames = 4L), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun healthySessionIsHealthy() {
        assertEquals(
            CaptureQuality.HEALTHY,
            policy.assess(capture(), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun warningMemoryMakesSessionDegraded() {
        assertEquals(
            CaptureQuality.DEGRADED,
            policy.assess(capture(), latency(100.0), memory(MemoryPressureLevel.WARNING))
        )
    }

    @Test
    fun highDropsMakeSessionUnsafe() {
        assertEquals(
            CaptureQuality.HEALTHY,
            policy.assess(capture(frames = 15L, drops = 9L), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun lowAcceptedThroughputIsDegraded() {
        assertEquals(
            CaptureQuality.DEGRADED,
            policy.assess(capture(frames = 10L, durationMs = 2_000L, drops = 9L), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun veryLowAcceptedThroughputIsUnsafe() {
        assertEquals(
            CaptureQuality.UNSAFE,
            policy.assess(capture(frames = 10L, durationMs = 3_000L, drops = 9L), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun longGapMakesSessionUnsafe() {
        assertEquals(
            CaptureQuality.UNSAFE,
            policy.assess(capture(maxGapMs = 3000L), latency(100.0), memory(MemoryPressureLevel.NORMAL))
        )
    }

    @Test
    fun highProcessingLatencyMakesSessionDegradedOrUnsafe() {
        assertEquals(
            CaptureQuality.DEGRADED,
            policy.assess(capture(), latency(750.0), memory(MemoryPressureLevel.NORMAL))
        )
        assertEquals(
            CaptureQuality.UNSAFE,
            policy.assess(capture(), latency(1500.0), memory(MemoryPressureLevel.NORMAL))
        )
    }
}
