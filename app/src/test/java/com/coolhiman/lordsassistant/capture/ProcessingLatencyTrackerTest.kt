package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingLatencyTrackerTest {
    @Test
    fun tracksBreakdownAndAverages() {
        val tracker = ProcessingLatencyTracker()
        tracker.record(10L, 20L, 30L)
        tracker.record(30L, 50L, 80L)

        val s = tracker.snapshot()
        assertEquals(2L, s.frames)
        assertEquals(30L, s.lastOcrMs)
        assertEquals(50L, s.lastScannerMs)
        assertEquals(80L, s.lastTotalMs)
        assertEquals(30L, s.maxOcrMs)
        assertEquals(50L, s.maxScannerMs)
        assertEquals(80L, s.maxTotalMs)
        assertEquals(20.0, s.averageOcrMs)
        assertEquals(35.0, s.averageScannerMs)
        assertEquals(55.0, s.averageTotalMs)
    }

    @Test
    fun clampsNegativeDurations() {
        val tracker = ProcessingLatencyTracker()
        tracker.record(-1L, -2L, -3L)
        val s = tracker.snapshot()
        assertEquals(0L, s.lastOcrMs)
        assertEquals(0L, s.lastScannerMs)
        assertEquals(0L, s.lastTotalMs)
    }
}
