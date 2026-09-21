package com.coolhiman.lordsassistant.capture

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class CaptureSessionDiagnosticsStoreTest {
    @Test
    fun roundTripPreservesSessionMetricsAndCompletedSessionState() {
        val context = RuntimeEnvironment.getApplication()
        val store = CaptureSessionDiagnosticsStore(context)
        store.clear()

        val capture = CaptureHealthSnapshot(
            sessionStarted = true,
            totalFrames = 42,
            acceptedFrames = 30,
            droppedFrames = 12,
            staleFrames = 2,
            processedFrames = 30,
            viewportResets = 1,
            lastFrameGapMs = 140L,
            maxFrameGapMs = 3200L,
            lastProcessingMs = 480L,
            maxProcessingMs = 900L,
            averageProcessingMs = 410.5,
            lastFrameAtMs = 5000L,
            sessionDurationMs = 4000L
        )
        val runtime = CaptureRuntimeSnapshot(
            sessionId = 7L,
            active = false,
            startCount = 2L,
            restartCount = 1L,
            stallCount = 1L,
            viewportChangeCount = 1L,
            lastStopReason = CaptureStopReason.CAPTURE_STALLED,
            lastFailureReason = "stall"
        )
        val latency = ProcessingLatencySnapshot(
            frames = 30L,
            lastOcrMs = 120L,
            lastScannerMs = 260L,
            lastTotalMs = 480L,
            maxOcrMs = 200L,
            maxScannerMs = 500L,
            maxTotalMs = 900L,
            averageOcrMs = 100.0,
            averageScannerMs = 220.0,
            averageTotalMs = 410.5
        )
        val snapshot = CaptureSessionDiagnostics.snapshot(
            capture = capture,
            runtime = runtime,
            latency = latency,
            quality = CaptureQuality.DEGRADED
        )

        assertEquals(true, store.save(snapshot))
        val restored = store.read()
        assertNotNull(restored)
        assertEquals(42L, restored!!.frames)
        assertEquals(12L, restored.droppedFrames)
        assertEquals(2L, restored.staleFrames)
        assertEquals(7L, restored.sessionId)
        assertEquals(false, restored.runtime.active)
        assertEquals(true, restored.capture.sessionStarted)
        assertEquals(1L, restored.restartCount)
        assertEquals(CaptureStopReason.CAPTURE_STALLED, restored.runtime.lastStopReason)
        assertEquals(CaptureQuality.DEGRADED, restored.quality)
        assertEquals(100.0, restored.averageOcrMs, 0.001)
    }
}
