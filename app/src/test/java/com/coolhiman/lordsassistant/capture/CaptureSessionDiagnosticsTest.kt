package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureSessionDiagnosticsTest {
    @Test
    fun snapshotRollsUpExistingTelemetryWithoutChangingIt() {
        val health = CaptureHealthSnapshot(
            sessionStarted = true,
            totalFrames = 10,
            acceptedFrames = 8,
            droppedFrames = 2,
            staleFrames = 1,
            processedFrames = 8,
            viewportResets = 1,
            lastFrameGapMs = 900L,
            maxFrameGapMs = 1200L,
            lastProcessingMs = 700L,
            maxProcessingMs = 900L,
            averageProcessingMs = 600.0,
            lastFrameAtMs = 9000L,
            sessionDurationMs = 8000L
        )
        val runtime = CaptureRuntimeSnapshot(
            sessionId = 4L,
            active = true,
            startCount = 2L,
            restartCount = 1L,
            stallCount = 1L,
            viewportChangeCount = 1L,
            lastStopReason = null,
            lastFailureReason = null
        )
        val latency = ProcessingLatencySnapshot(
            frames = 8L,
            lastOcrMs = 40L,
            lastScannerMs = 120L,
            lastTotalMs = 700L,
            maxOcrMs = 80L,
            maxScannerMs = 300L,
            maxTotalMs = 900L,
            averageOcrMs = 50.0,
            averageScannerMs = 150.0,
            averageTotalMs = 600.0
        )

        val snapshot = CaptureSessionDiagnostics.snapshot(
            capture = health,
            runtime = runtime,
            latency = latency,
            quality = CaptureQuality.DEGRADED
        )

        assertEquals(10L, snapshot.frames)
        assertEquals(8L, snapshot.acceptedFrames)
        assertEquals(20.0, snapshot.dropRatePercent)
        assertEquals(1L, snapshot.stallCount)
        assertEquals(1L, snapshot.viewportChangeCount)
        assertEquals(50.0, snapshot.averageOcrMs)
        assertEquals(150.0, snapshot.averageScannerMs)
        assertEquals(900L, snapshot.maxProcessingMs)
        assertTrue(snapshot.memorySafeForDiagnostics)
    }

    @Test
    fun unsafeQualityIsReflectedWithoutChangingTelemetry() {
        val health = CaptureHealthSnapshot(
            sessionStarted = true,
            totalFrames = 5,
            acceptedFrames = 5,
            droppedFrames = 0,
            staleFrames = 0,
            processedFrames = 5,
            viewportResets = 0,
            lastFrameGapMs = 1000L,
            maxFrameGapMs = 1000L,
            lastProcessingMs = 100L,
            maxProcessingMs = 100L,
            averageProcessingMs = 100.0,
            lastFrameAtMs = 5000L,
            sessionDurationMs = 4000L
        )
        val runtime = CaptureRuntimeSnapshot(1L, true, 1L, 0L, 0L, 0L, null, null)
        val latency = ProcessingLatencySnapshot(5L, 10L, 20L, 30L, 10L, 20L, 30L, 10.0, 20.0, 30.0)

        val snapshot = CaptureSessionDiagnostics.snapshot(
            health, runtime, latency, CaptureQuality.UNSAFE
        )

        assertEquals(CaptureQuality.UNSAFE, snapshot.quality)
        assertEquals(5L, snapshot.frames)
        assertTrue(!snapshot.memorySafeForDiagnostics)
    }
}
