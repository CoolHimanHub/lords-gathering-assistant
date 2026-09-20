package com.coolhiman.lordsassistant.target

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionManualRecoveryStoreTest {
    @Test
    fun resetRequestIsOneShot() {
        ActionManualRecoveryStore.clear()

        assertFalse(ActionManualRecoveryStore.consumeResetRequest())

        ActionManualRecoveryStore.requestReset()

        assertTrue(ActionManualRecoveryStore.consumeResetRequest())
        assertFalse(ActionManualRecoveryStore.consumeResetRequest())
    }
}
