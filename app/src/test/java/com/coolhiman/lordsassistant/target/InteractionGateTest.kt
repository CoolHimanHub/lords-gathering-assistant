package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionGateTest {
    @Test
    fun safeValidationWithPointAllowsInteraction() {
        val result = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT)
        assertTrue(InteractionGate.allow(result, ScreenPoint(500f, 400f)))
    }

    @Test
    fun blockedValidationNeverAllowsInteraction() {
        val result = TargetValidationResult(false, TargetValidationStage.POPUP_CONFIRMED)
        assertFalse(InteractionGate.allow(result, ScreenPoint(500f, 400f)))
    }

    @Test
    fun missingPointBlocksInteraction() {
        val result = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT)
        assertFalse(InteractionGate.allow(result, null))
    }
}
