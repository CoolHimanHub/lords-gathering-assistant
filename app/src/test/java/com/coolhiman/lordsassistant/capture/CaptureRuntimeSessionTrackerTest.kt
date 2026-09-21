package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureRuntimeSessionTrackerTest {
    @Test
    fun tracksSessionsRestartsAndFailureReasons() {
        val tracker = CaptureRuntimeSessionTracker()

        tracker.start()
        assertEquals(1L, tracker.snapshot().sessionId)
        assertEquals(0L, tracker.snapshot().restartCount)
        assertTrue(tracker.snapshot().active)

        tracker.recordStall()
        tracker.recordViewportChange()
        tracker.stop(CaptureStopReason.CAPTURE_STALLED)

        tracker.start()
        val snapshot = tracker.snapshot()
        assertEquals(2L, snapshot.sessionId)
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
        tracker.start()
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
