package com.coolhiman.lordsassistant.capture

import org.junit.Assert.assertEquals
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
}
