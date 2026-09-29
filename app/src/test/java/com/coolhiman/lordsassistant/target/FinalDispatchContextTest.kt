package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalDispatchContextTest {
    private val selected = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(900f, 600f),
        semanticIdentity = "WOOD"
    )

    private val observation = MapObservation(
        coordinate = selected.coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        screenPoint = selected.point,
        confidence = 0.95f,
        occupied = false,
        incomingTroops = false,
        label = "WOOD",
        coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 4.0),
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED),
        timestampMs = 10_000L
    )

    @Test
    fun dispatchContextCarriesFinalDispatchValidation() {
        val originalValidation = TargetValidationResult(
            safe = true,
            stage = TargetValidationStage.SAFE_TO_INTERACT,
            validatedAtMs = 10_000L
        )
        val orchestrator = ActionOrchestrator()
        var context: FinalDispatchContext? = null

        orchestrator.request(
            automaticActionsEnabled = true,
            selected = selected,
            validation = originalValidation,
            beforeObservation = observation,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 10_000L
        )

        orchestrator.revalidate(
            latestObservation = observation,
            latestValidation = originalValidation,
            latestAction = ActionButton(ActionKind.GATHER, selected.point, 0.95f),
            nowMs = 10_000L
        )

        val result = orchestrator.dispatch(
            nowMs = 10_001L,
            automaticActionsEnabled = true
        ) {
            context = it
            true
        }

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
        assertNotNull(context)
        assertTrue(context!!.latestValidation.safe)
        assertEquals(TargetValidationStage.SAFE_TO_INTERACT, context!!.latestValidation.stage)
        assertEquals(10_000L, context!!.revalidatedAtMs)
    }
}
