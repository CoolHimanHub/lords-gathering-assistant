package com.coolhiman.lordsassistant.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.10 post-dispatch verification and recovery coverage.
 *
 * These tests exercise the lifecycle boundary after guarded dispatch. They
 * never invoke an Accessibility gesture or enable automatic gameplay.
 */
class PostDispatchVerificationRecoveryIntegrationTest {

    @Test
    fun successfulEvidenceRequiresTwoConsistentFramesBeforeSuccess() {
        val lifecycle = ActionLifecycleController()

        lifecycle.request(
            automaticActionsEnabled = true,
            selected = target(),
            validation = safeValidation(),
            nowMs = 1_000L
        )
        assertEquals(ActionLifecycleState.REQUESTED, lifecycle.snapshot.state)

        lifecycle.revalidated(safeValidation())
        assertEquals(ActionLifecycleState.REVALIDATED, lifecycle.snapshot.state)

        lifecycle.dispatched(1_001L, dispatchAccepted = true)
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, lifecycle.snapshot.state)

        val evidence = setOf(
            PostActionEvidence.POPUP_DISAPPEARED,
            PostActionEvidence.TARGET_REMOVED
        )

        lifecycle.verify(evidence)
        assertEquals(ActionLifecycleState.SUCCEEDED, lifecycle.snapshot.state)
    }

    @Test
    fun explicitActionRejectionProducesFailedAndAllowsFutureAttempt() {
        val lifecycle = ActionLifecycleController()

        lifecycle.request(
            automaticActionsEnabled = true,
            selected = target(),
            validation = safeValidation(),
            nowMs = 2_000L
        )
        lifecycle.revalidated(safeValidation())
        lifecycle.dispatched(2_001L, dispatchAccepted = true)

        val failed = lifecycle.verify(setOf(PostActionEvidence.ACTION_REJECTED))
        assertEquals(ActionLifecycleState.FAILED, failed.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_FAILED, failed.failure)
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(failed))
    }

    @Test
    fun timeoutProducesUnknownAndBlocksAutomaticRetryUntilRecoveryReset() {
        val orchestrator = ActionOrchestrator()

        val requested = orchestrator.request(
            automaticActionsEnabled = true,
            selected = target(),
            validation = safeValidation(),
            beforeObservation = null,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 3_000L
        )
        assertEquals(ActionLifecycleState.REQUESTED, requested.lifecycle.state)

        orchestrator.revalidate(
            latestObservation = null,
            latestValidation = safeValidation(),
            latestAction = null
        )
        assertEquals(ActionLifecycleState.REVALIDATED, orchestrator.lifecycleSnapshot.state)

        orchestrator.dispatch(3_001L) { true }
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, orchestrator.lifecycleSnapshot.state)

        val timedOut = orchestrator.verifyPostAction(
            afterObservation = null,
            popupAfter = null,
            nowMs = 7_001L
        )
        assertEquals(ActionLifecycleState.UNKNOWN, timedOut.lifecycle.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_TIMEOUT, timedOut.lifecycle.failure)
        assertFalse(ActionRecoveryPolicy.mayStartAutomaticAttempt(timedOut.lifecycle))

        val blocked = orchestrator.request(
            automaticActionsEnabled = true,
            selected = target(),
            validation = safeValidation(),
            beforeObservation = null,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 7_002L
        )
        assertEquals(ActionLifecycleState.UNKNOWN, blocked.lifecycle.state)

        val recovered = orchestrator.reset()
        assertEquals(ActionLifecycleState.IDLE, recovered.lifecycle.state)
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(recovered.lifecycle))
        assertEquals(1L, orchestrator.currentRecoveryEpoch)
    }

    @Test
    fun failedDispatchCannotEnterPostActionVerificationOrCreateRetryableSuccess() {
        val orchestrator = ActionOrchestrator()

        orchestrator.request(
            automaticActionsEnabled = true,
            selected = target(),
            validation = safeValidation(),
            beforeObservation = null,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 4_000L
        )
        orchestrator.revalidate(
            latestObservation = null,
            latestValidation = safeValidation(),
            latestAction = null
        )

        val dispatched = orchestrator.dispatch(4_001L) { false }
        assertEquals(ActionLifecycleState.FAILED, dispatched.lifecycle.state)
        assertEquals(ActionLifecycleFailure.DISPATCH_FAILED, dispatched.lifecycle.failure)

        val verification = orchestrator.verifyPostAction(
            afterObservation = null,
            popupAfter = null,
            nowMs = 4_002L
        )
        assertEquals(ActionLifecycleState.FAILED, verification.lifecycle.state)
        assertFalse(ActionRecoveryPolicy.mayStartAutomaticAttempt(
            ActionLifecycleSnapshot(ActionLifecycleState.UNKNOWN)
        ))
    }

    private fun target() = ActionTargetSnapshot(
        coordinate = com.coolhiman.lordsassistant.model.WorldCoordinate(355, 167, 511),
        kind = com.coolhiman.lordsassistant.model.TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = com.coolhiman.lordsassistant.model.ScreenPoint(500f, 400f)
    )

    private fun safeValidation() = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )
}
