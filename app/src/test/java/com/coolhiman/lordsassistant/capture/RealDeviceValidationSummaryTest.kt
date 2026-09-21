package com.coolhiman.lordsassistant.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDeviceValidationSummaryTest {
    @Test
    fun aggregatesRejectionsAndKeepsDiagnosticBoundary() {
        val summary = RealDeviceValidationSummary(
            capture = null,
            rejectionCounts = mapOf(
                "CAMERA_CONTINUITY_INVALID" to 2,
                "VALIDATION_UNSAFE" to 3
            ),
            action = null
        )
        assertEquals(5, summary.totalRejections)
        val formatted = summary.format()
        assert(formatted.contains("CAMERA_CONTINUITY_INVALID = 2"))
        assert(formatted.contains("VALIDATION_UNSAFE = 3"))
        assert(formatted.contains("no action authority"))
    }
    @Test
    fun formatsExplicitCaptureSessionState() {
        val health = CaptureHealthSnapshot(false, 1L, 1L, 0L, 0L, 1L, 0L, 100L, 100L, 50L, 50L, 50.0, 100L, 100L)
        val runtime = CaptureRuntimeSnapshot(9L, false, 1L, 0L, 0L, 0L, CaptureStopReason.USER_STOP, null)
        val latency = ProcessingLatencySnapshot(1L, 5L, 10L, 15L, 5L, 10L, 15L, 5.0, 10.0, 15.0)
        val capture = CaptureSessionDiagnostics.snapshot(health, runtime, latency, CaptureQuality.HEALTHY)
        val formatted = RealDeviceValidationSummary(capture, emptyMap(), null).format()
        assertTrue(formatted.contains("state=completed"))
    }

    @Test
    fun correlatesCompletedSessionHistoryReadOnly() {
        val health = CaptureHealthSnapshot(false, 1L, 1L, 0L, 0L, 1L, 0L, 100L, 100L, 50L, 50L, 50.0, 100L, 100L)
        val latency = ProcessingLatencySnapshot(1L, 5L, 10L, 15L, 5L, 10L, 15L, 5.0, 10.0, 15.0)
        val first = CaptureSessionDiagnostics.snapshot(health, CaptureRuntimeSnapshot(11L, false, 1L, 0L, 0L, 0L, CaptureStopReason.USER_STOP, null), latency, CaptureQuality.HEALTHY)
        val second = CaptureSessionDiagnostics.snapshot(health, CaptureRuntimeSnapshot(12L, false, 1L, 0L, 1L, 0L, CaptureStopReason.CAPTURE_STALLED, "stall"), latency, CaptureQuality.DEGRADED)
        val formatted = RealDeviceValidationSummary(null, emptyMap(), null, listOf(first, second)).format()
        assertTrue(formatted.contains("Completed sessions: 2"))
        assertTrue(formatted.contains("HEALTHY=1"))
        assertTrue(formatted.contains("DEGRADED=1"))
        assertTrue(formatted.contains("CAPTURE_STALLED=1"))
        assertTrue(formatted.contains("no action authority"))
    }
}
