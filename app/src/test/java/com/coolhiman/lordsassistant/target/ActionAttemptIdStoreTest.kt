package com.coolhiman.lordsassistant.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionAttemptIdStoreTest {
    @Test
    fun nextIdAdvancesFromHighestKnownValue() {
        assertEquals(18L, ActionAttemptIdStore.nextId(17L, 12L))
        assertEquals(19L, ActionAttemptIdStore.nextId(12L, 18L))
    }

    @Test
    fun nextIdFailsClosedAtLongMaxValue() {
        assertNull(ActionAttemptIdStore.nextId(Long.MAX_VALUE, 0L))
        assertNull(ActionAttemptIdStore.nextId(0L, Long.MAX_VALUE))
        assertNull(ActionAttemptIdStore.nextId(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun nextIdDoesNotWrapToNegativeIdentity() {
        assertEquals(Long.MAX_VALUE, ActionAttemptIdStore.nextId(Long.MAX_VALUE - 1L, 0L))
    }
}
