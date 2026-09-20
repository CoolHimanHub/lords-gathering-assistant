package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.map.LiveMapScanResult
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState
import com.coolhiman.lordsassistant.vision.MarchAssociationDiagnostics
import com.coolhiman.lordsassistant.vision.MarchAssociationStatus
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDiagnosticsSnapshotTest {
    @Test
    fun formatterExposesSafetyAndEvidenceFields() {
        val selected = ActionTargetSnapshot(
            WorldCoordinate(355, 167, 511),
            TargetKind.RESOURCE,
            3,
            ActionKind.GATHER,
            ScreenPoint(100f, 200f)
        )
        val lifecycle = ActionLifecycleSnapshot(
            ActionLifecycleState.WAITING_FOR_RESULT,
            selected
        )
        val scan = LiveMapScanResult(
            observations = listOf(
                MapObservation(
                    coordinate = selected.coordinate,
                    screenPoint = selected.point,
                    label = ResourceType.WOOD.name,
                    level = 3,
                    quantity = null,
                    occupied = false,
                    incomingTroops = false,
                    kind = TargetKind.RESOURCE,
                    confidence = 0.95f,
                    evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
                )
            ),
            plan = TargetPlan(emptyList(), emptyList()),
            detectedTiles = 1,
            processingMs = 42L,
            origin = selected.coordinate,
            cameraState = CameraState.STABLE,
            cameraSharedTargets = 4,
            validation = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT),
            actionButton = ActionButton(ActionKind.GATHER, RectF(90f, 190f, 110f, 210f), selected.point, 0.9f),
            selectedActionTarget = selected,
            selectedObservation = null,
            marchSignals = emptyList(),
            popupState = PopupState(isPopup = true, kind = TargetKind.RESOURCE, resource = ResourceType.WOOD, level = 3),
            selectedMarchAssociation = MarchAssociationDiagnostics(MarchAssociationStatus.CLEAR_MARCH, 28f, 70f, 0.40f)
        )
        val text = ActionDiagnosticsFormatter.format(
            ActionDiagnosticsSnapshot.fromScan(scan, lifecycle, null, 123L)
        )
        assertTrue(text.contains("SAFE_TO_INTERACT"))
        assertTrue(text.contains("GATHER"))
        assertTrue(text.contains("STABLE"))
        assertTrue(text.contains("L3"))
        assertTrue(text.contains("CLEAR_MARCH"))
        assertTrue(text.contains("Nearest march: 28.0 px"))
        assertTrue(text.contains("Association ratio: 0.40"))
        assertTrue(text.contains("Target stability: 0 frames"))
        assertTrue(text.contains("Action attempt: none"))
    }
}
