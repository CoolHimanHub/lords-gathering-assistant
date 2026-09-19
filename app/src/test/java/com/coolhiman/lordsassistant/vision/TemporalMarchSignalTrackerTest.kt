package com.coolhiman.lordsassistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalMarchSignalTrackerTest {
    @Test
    fun singleFrameSignalIsNotStable() {
        val tracker = TemporalMarchSignalTracker(confirmHits = 2)
        val signal = MarchSignal(200f, 300f, 50.0, 0.8f)
        assertTrue(tracker.update(listOf(signal), 1000L).isEmpty())
    }

    @Test
    fun repeatedNearbySignalBecomesStable() {
        val tracker = TemporalMarchSignalTracker(confirmHits = 2)
        val first = MarchSignal(200f, 300f, 50.0, 0.8f)
        val second = MarchSignal(205f, 304f, 60.0, 0.85f)

        assertTrue(tracker.update(listOf(first), 1000L).isEmpty())
        val stable = tracker.update(listOf(second), 1200L)

        assertEquals(1, stable.size)
        assertEquals(205f, stable.single().x, 0.01f)
    }

    @Test
    fun staleSignalMustRestartConfirmation() {
        val tracker = TemporalMarchSignalTracker(confirmHits = 2, maxGapMs = 500L)
        val signal = MarchSignal(200f, 300f, 50.0, 0.8f)

        assertTrue(tracker.update(listOf(signal), 1000L).isEmpty())
        assertTrue(tracker.update(listOf(signal), 1601L).isEmpty())
    }
}
