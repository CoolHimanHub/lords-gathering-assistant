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
        assertEquals(2L, snapshot.processedFrames)
        assertEquals(1L, snapshot.viewportResets)
        assertEquals(600L, snapshot.lastFrameGapMs)
        assertEquals(600L, snapshot.maxFrameGapMs)
        assertEquals(60L, snapshot.lastProcessingMs)
        assertEquals(60L, snapshot.maxProcessingMs)
        assertEquals(50.0, snapshot.averageProcessingMs)
        assertEquals(33.333333333333336, snapshot.dropRatePercent)
    }

    @Test
    fun resetClearsSessionState() {
        val tracker = CaptureHealthTracker()
        tracker.start(10L)
        tracker.frameArrived(10L)
        tracker.frameDropped()
        tracker.reset()

        val snapshot = tracker.snapshot()
        assertTrue(!snapshot.sessionStarted)
        assertEquals(0L, snapshot.totalFrames)
        assertEquals(0L, snapshot.droppedFrames)
        assertEquals(null, snapshot.lastFrameAtMs)
    }
}
