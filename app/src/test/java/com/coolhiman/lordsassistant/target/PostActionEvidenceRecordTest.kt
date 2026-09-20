package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.MarchSignal
import com.coolhiman.lordsassistant.vision.PopupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostActionEvidenceRecordTest {
    private val selected = ActionTargetSnapshot(
        WorldCoordinate(355, 167, 511),
        TargetKind.RESOURCE,
        3,
        ActionKind.GATHER,
        ScreenPoint(900f, 600f)
    )

    @Test
    fun successfulOwnMarchEvidenceRecordsItsSourceAndTargetIdentity() {
        val observation = MapObservation(
            coordinate = selected.coordinate,
            kind = selected.kind,
            level = selected.level,
            screenPoint = selected.point,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = ResourceType.WOOD,
            level = 3,
            quantity = 720000L,
            occupied = false,
            incomingTroops = false,
            coordinate = selected.coordinate,
            isPopup = true
        )

        val orchestrator = ActionOrchestrator()
        val validation = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT)
        orchestrator.request(true, selected, validation, observation, popup, emptyList(), 10_000L)
        orchestrator.revalidate(observation, validation, ActionButton(ActionKind.GATHER, selected.point, 0.95f))
        orchestrator.dispatch(10_001L) { true }
        orchestrator.observeMarch(listOf(MarchSignal(910f, 600f, 20.0, 0.9f)), 10_100L)
        orchestrator.observeMarch(listOf(MarchSignal(920f, 600f, 20.0, 0.9f)), 10_200L)

        orchestrator.verifyPostAction(observation, popup, 10_300L)
        orchestrator.verifyPostAction(observation, popup, 10_400L)

        val record = orchestrator.lastPostActionEvidence
        assertTrue(record != null)
        assertTrue(PostActionEvidence.OWN_MARCH_CONFIRMED in record!!.evidence)
        assertTrue(PostActionEvidenceSource.MARCH_ASSOCIATION in record.sources)
        assertEquals(selected, record.selected)
        assertEquals(2, record.confirmingFrames)
    }
}
