package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureRuntimeSessionTrackerTest {
    @Test
    fun tracksSessionsRestartsAndFailureReasons() {
        val tracker = CaptureRuntimeSessionTracker()

        tracker.start(41L)
        assertEquals(41L, tracker.snapshot().sessionId)
        assertEquals(0L, tracker.snapshot().restartCount)
        assertTrue(tracker.snapshot().active)

        tracker.recordStall()
        val stalled = tracker.snapshot()
        assertEquals(1L, stalled.stallCount)
        assertEquals(CaptureStopReason.CAPTURE_STALLED.name, stalled.lastFailureReason)

        tracker.recordViewportChange()
        tracker.stop(CaptureStopReason.CAPTURE_STALLED)
        assertEquals(CaptureStopReason.CAPTURE_STALLED, tracker.snapshot().lastStopReason)

        tracker.start(42L)
        val snapshot = tracker.snapshot()
        assertEquals(42L, snapshot.sessionId)
        assertEquals(2L, snapshot.startCount)
        assertEquals(1L, snapshot.restartCount)
        assertEquals(1L, snapshot.stallCount)
        assertEquals(1L, snapshot.viewportChangeCount)
        assertTrue(snapshot.active)
        assertEquals(null, snapshot.lastStopReason)
        assertEquals(null, snapshot.lastFailureReason)
    }

    @Test
    fun resetClearsRuntimeDiagnostics() {
        val tracker = CaptureRuntimeSessionTracker()
        tracker.start(7L)
        tracker.recordFailure("reader setup failed")
        tracker.stop(CaptureStopReason.CAPTURE_SETUP_FAILED)
        tracker.reset()

        val snapshot = tracker.snapshot()
        assertEquals(0L, snapshot.sessionId)
        assertFalse(snapshot.active)
        assertEquals(0L, snapshot.startCount)
        assertEquals(null, snapshot.lastStopReason)
        assertEquals(null, snapshot.lastFailureReason)
    }
}
