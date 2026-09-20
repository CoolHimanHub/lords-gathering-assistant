package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutionControllerTest {
    private val controller = ActionExecutionController()
    private val target = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(500f, 400f)
    )
    private val safe = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )

    @Test
    fun disabledAutomationIsAlwaysBlocked() {
        val result = controller.request(false, target, safe, 1000L)
        assertEquals(ActionExecutionBlockReason.AUTOMATION_DISABLED, result.reason)
    }

    @Test
    fun invalidTargetIsBlocked() {
        val result = controller.request(
            true,
            target,
            TargetValidationResult(false, TargetValidationStage.POPUP_CONFIRMED),
            1000L
        )
        assertEquals(ActionExecutionBlockReason.TARGET_INVALID, result.reason)
    }

    @Test
    fun firstEligibleRequestIsAllowed() {
        val result = controller.request(true, target, safe, 1000L)
        assertTrue(result.allowed)
    }

    @Test
    fun cooldownBlocksImmediateRepeat() {
        controller.markDispatched(target, 1000L)

        val result = controller.request(true, target, safe, 1800L)
        assertEquals(ActionExecutionBlockReason.COOLDOWN, result.reason)
    }

    @Test
    fun duplicateTargetIsBlockedAfterCooldown() {
        controller.markDispatched(target, 1000L)

        val result = controller.request(true, target, safe, 2200L)
        assertEquals(ActionExecutionBlockReason.DUPLICATE_TARGET, result.reason)
    }

    @Test
    fun differentTargetCanProceedAfterCooldown() {
        controller.markDispatched(target, 1000L)

        val different = target.copy(
            coordinate = WorldCoordinate(355, 168, 511)
        )
        val result = controller.request(true, different, safe, 2600L)
        assertTrue(result.allowed)
    }

    @Test
    fun resetClearsDispatchHistory() {
        controller.markDispatched(target, 1000L)
        controller.reset()

        val result = controller.request(true, target, safe, 1100L)
        assertTrue(result.allowed)
    }
}
