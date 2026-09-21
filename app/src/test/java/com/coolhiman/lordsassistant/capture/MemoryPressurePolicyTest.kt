package com.coolhiman.lordsassistant.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryPressurePolicyTest {
    @Test
    fun classifiesMemoryAtBoundaries() {
        val policy = MemoryPressurePolicy()

        assertEquals(MemoryPressureLevel.NORMAL, policy.evaluate(79L, 100L).level)
        assertEquals(MemoryPressureLevel.WARNING, policy.evaluate(80L, 100L).level)
        assertEquals(MemoryPressureLevel.WARNING, policy.evaluate(89L, 100L).level)
        assertEquals(MemoryPressureLevel.CRITICAL, policy.evaluate(90L, 100L).level)
    }

    @Test
    fun clampsInvalidNegativeUsageWithoutChangingMaximum() {
        val snapshot = MemoryPressurePolicy().evaluate(-10L, 100L)

        assertEquals(0L, snapshot.usedBytes)
        assertEquals(100L, snapshot.maxBytes)
        assertEquals(MemoryPressureLevel.NORMAL, snapshot.level)
    }
}
