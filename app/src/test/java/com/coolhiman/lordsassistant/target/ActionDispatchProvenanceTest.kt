package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatchProvenanceTest {
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
    fun provenanceMatchesOnlyItsOwnAttemptAndEpoch() {
        val orchestrator = ActionOrchestrator(initialRecoveryEpoch = 12L)
        val result = orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            captureSessionId = 7001L,
            nowMs = 80_000L
        )
        val provenance = ActionDispatchProvenance(1L, 12L, 80_001L, captureSessionId = 7001L)

        assertTrue(provenance.matches(result.session))
        assertFalse(ActionDispatchProvenance(2L, 12L, 80_001L).matches(result.session))
        assertFalse(ActionDispatchProvenance(1L, 13L, 80_001L).matches(result.session))
        assertFalse(ActionDispatchProvenance(1L, 12L, 80_001L, captureSessionId = 7002L).matches(result.session))
        assertFalse(ActionDispatchProvenance(1L, 12L, 80_001L).matches(null))
    }

    @Test
    fun missingCaptureSessionCannotMatchLiveSession() {
        val orchestrator = ActionOrchestrator(initialRecoveryEpoch = 22L)
        val result = orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            captureSessionId = 8001L,
            nowMs = 82_000L
        )

        val legacy = ActionDispatchProvenance(
            attemptId = result.session!!.attemptId,
            recoveryEpoch = 22L,
            startedAtMs = 82_001L
        )

        assertFalse(legacy.matches(result.session))
    }

    @Test
    fun recoveryEpochMismatchBlocksStalePreDispatchProvenance() {
        val orchestrator = ActionOrchestrator(initialRecoveryEpoch = 21L)
        val result = orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = safeValidation,
            beforeObservation = observation,
            popupBefore = popup,
            baselineMarchSignals = emptyList(),
            nowMs = 81_000L
        )

        val stale = ActionDispatchProvenance(
            attemptId = result.session!!.attemptId,
            recoveryEpoch = 20L,
            startedAtMs = 81_001L
        )

        assertFalse(stale.matches(result.session))
    }
}
