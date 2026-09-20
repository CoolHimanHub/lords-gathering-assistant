package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLifecycleControllerTest {
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
    fun lifecycleReachesWaitingOnlyAfterRevalidationAndDispatch() {
        val controller = ActionLifecycleController()

        assertEquals(
            ActionLifecycleState.REQUESTED,
            controller.request(true, target, safe, 1000L).state
        )
        assertEquals(
            ActionLifecycleState.REVALIDATED,
            controller.revalidated(safe).state
        )
        assertEquals(
            ActionLifecycleState.WAITING_FOR_RESULT,
            controller.dispatched(1000L, true).state
        )
    }

    @Test
    fun failedRevalidationStopsLifecycle() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)

        val result = controller.revalidated(
            TargetValidationResult(false, TargetValidationStage.POPUP_CONFIRMED)
        )

        assertEquals(ActionLifecycleState.FAILED, result.state)
        assertEquals(ActionLifecycleFailure.REVALIDATION_FAILED, result.failure)
    }

    @Test
    fun dispatchFailureStopsLifecycle() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)

        val result = controller.dispatched(1000L, false)

        assertEquals(ActionLifecycleState.FAILED, result.state)
        assertEquals(ActionLifecycleFailure.DISPATCH_FAILED, result.failure)
    }

    @Test
    fun ownMarchIsPositiveSuccessEvidence() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)
        controller.dispatched(1000L, true)

        assertEquals(
            ActionLifecycleState.SUCCEEDED,
            controller.verify(setOf(PostActionEvidence.OWN_MARCH_CONFIRMED)).state
        )
    }

    @Test
    fun popupDisappearanceWithoutStateChangeRemainsUnknown() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)
        controller.dispatched(1000L, true)

        assertEquals(
            ActionLifecycleState.UNKNOWN,
            controller.verify(setOf(PostActionEvidence.POPUP_DISAPPEARED)).state
        )
    }

    @Test
    fun popupDisappearanceWithTargetRemovalIsSuccess() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)
        controller.dispatched(1000L, true)

        assertEquals(
            ActionLifecycleState.SUCCEEDED,
            controller.verify(
                setOf(
                    PostActionEvidence.POPUP_DISAPPEARED,
                    PostActionEvidence.TARGET_REMOVED
                )
            ).state
        )
    }

    @Test
    fun rejectionIsFailure() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)
        controller.dispatched(1000L, true)

        assertEquals(
            ActionLifecycleState.FAILED,
            controller.verify(setOf(PostActionEvidence.ACTION_REJECTED)).state
        )
    }

    @Test
    fun timeoutBecomesUnknown() {
        val controller = ActionLifecycleController()
        controller.request(true, target, safe, 1000L)
        controller.revalidated(safe)
        controller.dispatched(1000L, true)

        val result = controller.timeout()

        assertEquals(ActionLifecycleState.UNKNOWN, result.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_TIMEOUT, result.failure)
    }

    @Test
    fun disabledExecutionNeverStartsLifecycle() {
        val controller = ActionLifecycleController()

        val result = controller.request(false, target, safe, 1000L)

        assertTrue(result.state == ActionLifecycleState.FAILED)
        assertEquals(ActionLifecycleFailure.EXECUTION_BLOCKED, result.failure)
    }

    @Test
    fun restartRecoveryRestoresNonRetryableUnknownState() {
        val controller = ActionLifecycleController()

        val result = controller.restoreUnknown()

        assertEquals(ActionLifecycleState.UNKNOWN, result.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_TIMEOUT, result.failure)
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(result.state))
    }


    @Test
    fun recoveryEpochExhaustionCannotAutomaticallyRetry() {
        val controller = ActionLifecycleController()
        val result = controller.recoveryEpochExhausted()

        assertEquals(ActionLifecycleState.FAILED, result.state)
        assertEquals(ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED, result.failure)
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(result))
    }

    @Test
    fun recoveryPolicyAllowsOnlySafeTerminalRecoveryStates() {
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.IDLE))
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.SUCCEEDED))
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.FAILED))

        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.REQUESTED))
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.REVALIDATED))
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.WAITING_FOR_RESULT))
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(ActionLifecycleState.UNKNOWN))
    }
}
