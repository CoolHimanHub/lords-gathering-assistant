package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureHealthTrackerTest {
    @Test
    fun recordsFramesDropsAndProcessingTiming() {
        val tracker = CaptureHealthTracker()
        tracker.start(1000L)

        tracker.frameArrived(1000L)
        tracker.frameAccepted()
        tracker.processingFinished(40L)

        tracker.frameArrived(1500L)
        tracker.frameDropped()

        tracker.frameArrived(2100L)
        tracker.frameAccepted()
        tracker.processingFinished(60L)
        tracker.viewportReset()

        val snapshot = tracker.snapshot()
        assertEquals(3L, snapshot.totalFrames)
        assertEquals(2L, snapshot.acceptedFrames)
        assertEquals(1L, snapshot.droppedFrames)
        assertEquals(0L, snapshot.staleFrames)
        assertEquals(2L, snapshot.processedFrames)
        assertEquals(1L, snapshot.viewportResets)
        assertEquals(600L, snapshot.lastFrameGapMs)
        assertEquals(600L, snapshot.maxFrameGapMs)
        assertEquals(60L, snapshot.lastProcessingMs)
        assertEquals(60L, snapshot.maxProcessingMs)
        assertEquals(1100L, snapshot.sessionDurationMs)
        assertEquals(2.727272727272727, snapshot.framesPerSecond)
        assertEquals(50.0, snapshot.averageProcessingMs)
        assertEquals(33.333333333333336, snapshot.dropRatePercent)
    }

    @Test
    fun recordsStaleFramesSeparately() {
        val tracker = CaptureHealthTracker()
        tracker.start(100L)
        tracker.frameArrived(100L)
        tracker.frameDropped(stale = true)

        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.droppedFrames)
        assertEquals(1L, snapshot.staleFrames)
    }

    @Test
    fun resetClearsSessionState() {
        val tracker = CaptureHealthTracker()
        tracker.start(10L)
        tracker.frameArrived(10L)
        tracker.frameDropped(stale = true)
        tracker.reset()

        val snapshot = tracker.snapshot()
        assertTrue(!snapshot.sessionStarted)
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.droppedFrames)
        assertEquals(null, snapshot.lastFrameAtMs)
        assertEquals(0L, snapshot.sessionDurationMs)
        assertEquals(0.0, snapshot.framesPerSecond)
    }

    @Test
    fun watchdogTripsOnceAfterCaptureStall() {
        val watchdog = CaptureWatchdog(stallTimeoutMs = 3000L)
        watchdog.start(1000L)

        assertTrue(!watchdog.check(3999L))
        assertTrue(watchdog.check(4000L))
        assertTrue(!watchdog.check(5000L))

        watchdog.frameArrived(5100L)
        assertTrue(!watchdog.check(8099L))
        assertTrue(watchdog.check(8100L))
    }

    @Test
    fun watchdogStopsAndIgnoresLateChecks() {
        val watchdog = CaptureWatchdog(stallTimeoutMs = 1000L)
        watchdog.start(100L)
        watchdog.stop()

        assertTrue(!watchdog.isActive())
        assertTrue(!watchdog.isStalled())
        assertTrue(!watchdog.check(5000L))
    }
}
