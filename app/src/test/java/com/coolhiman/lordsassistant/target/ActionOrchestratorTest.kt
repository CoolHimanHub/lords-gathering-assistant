package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.MarchSignal
import com.coolhiman.lordsassistant.vision.PopupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionOrchestratorTest {
    private val selected = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(900f, 600f)
    )

    private val observation = MapObservation(
        coordinate = selected.coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        screenPoint = selected.point,
        confidence = 0.95f,
        occupied = false,
        incomingTroops = false
    )

    private val popup = PopupState(
        kind = TargetKind.RESOURCE,
        resource = ResourceType.WOOD,
        level = 3,
        quantity = 720000L,
        occupied = false,
        incomingTroops = false,
        coordinate = selected.coordinate,
        isPopup = true
    )

    private val safeValidation = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )

    @Test
    fun disabledAutomationStopsBeforeDispatch() {
        val orchestrator = ActionOrchestrator()
        val result = orchestrator.request(
            automaticActionsEnabled = false,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            nowMs = 0L
        )

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        var dispatched = false
        orchestrator.dispatch(1L) {
            dispatched = true
            true
        }
        assertFalse(dispatched)
    }

    @Test
    fun safeFlowRevalidatesDispatchesAndConfirmsOwnMarch() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            nowMs = 10_000L
        )

        orchestrator.revalidate(
            latestObservation = observation,
            latestValidation = safeValidation,
            latestAction = ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        assertEquals(ActionLifecycleState.REVALIDATED, orchestrator.lifecycleSnapshot.state)

        orchestrator.dispatch(10_001L) { true }
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, orchestrator.lifecycleSnapshot.state)

        val first = MarchSignal(910f, 600f, 20.0, 0.9f)
        val second = MarchSignal(920f, 600f, 20.0, 0.9f)
        orchestrator.observeMarch(listOf(first), 10_100L)
        orchestrator.observeMarch(listOf(second), 10_200L)

        val firstResult = orchestrator.verifyPostAction(
            afterObservation = observation,
            popupAfter = popup,
            nowMs = 10_300L
        )
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, firstResult.lifecycle.state)

        val result = orchestrator.verifyPostAction(
            afterObservation = observation,
            popupAfter = popup,
            nowMs = 10_400L
        )

        assertEquals(ActionLifecycleState.SUCCEEDED, result.lifecycle.state)
        assertTrue(result.session?.ownMarchConfirmed == true)
    }

    @Test
    fun popupDisappearanceAndTargetChangeCanConfirmSuccess() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            nowMs = 20_000L
        )
        orchestrator.revalidate(observation, safeValidation, ActionButton(ActionKind.GATHER, selected.point, 0.95f))
        orchestrator.dispatch(20_001L) { true }

        val after = observation.copy(coordinate = WorldCoordinate(355, 168, 511))
        val first = orchestrator.verifyPostAction(after, null, 20_100L)
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, first.lifecycle.state)

        val result = orchestrator.verifyPostAction(after, null, 20_200L)
        assertEquals(ActionLifecycleState.SUCCEEDED, result.lifecycle.state)
    }

    @Test
    fun completedTargetIsNotImmediatelyDispatchedAgain() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 40_000L)
        orchestrator.revalidate(observation, safeValidation, ActionButton(ActionKind.GATHER, selected.point, 0.95f))
        orchestrator.dispatch(40_001L) { true }
        orchestrator.observeMarch(listOf(MarchSignal(910f, 600f, 20.0, 0.9f)), 40_100L)
        orchestrator.observeMarch(listOf(MarchSignal(920f, 600f, 20.0, 0.9f)), 40_200L)
        orchestrator.verifyPostAction(observation, popup)

        var dispatched = false
        val result = orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 40_500L)
        orchestrator.dispatch(40_501L) {
            dispatched = true
            true
        }

        assertEquals(ActionLifecycleState.SUCCEEDED, result.lifecycle.state)
        assertFalse(dispatched)
    }

    @Test
    fun popupDisappearanceAloneRemainsUnknown() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            nowMs = 30_000L
        )
        orchestrator.revalidate(observation, safeValidation, ActionButton(ActionKind.GATHER, selected.point, 0.95f))
        orchestrator.dispatch(30_001L) { true }

        val result = orchestrator.verifyPostAction(observation, null)

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
    }

    @Test
    fun inconclusiveObservationTimesOutWithoutRetrying() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 50_000L)
        orchestrator.revalidate(observation, safeValidation, ActionButton(ActionKind.GATHER, selected.point, 0.95f))
        orchestrator.dispatch(50_001L) { true }

        val result = orchestrator.verifyPostAction(
            afterObservation = observation,
            popupAfter = popup,
            nowMs = 50_001L + ActionOrchestrator.POST_ACTION_TIMEOUT_MS
        )

        assertEquals(ActionLifecycleState.UNKNOWN, result.lifecycle.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_TIMEOUT, result.lifecycle.failure)
    }

    private fun ActionOrchestrator.sessionState(): ActionLifecycleState =
        sessionSnapshot().state

    private fun ActionOrchestrator.sessionSnapshot(): ActionLifecycleSnapshot =
        javaClass.getDeclaredField("lifecycle").let { field ->
            field.isAccessible = true
            (field.get(this) as ActionLifecycleController).snapshot
        }
}
