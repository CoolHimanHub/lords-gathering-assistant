package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.LiveActionCandidate
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveActionCandidateReconcilerTest {
    private val coordinate = WorldCoordinate(355, 167, 511)
    private val point = ScreenPoint(900f, 600f)
    private val observation = MapObservation(
        coordinate = coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        screenPoint = point,
        confidence = 0.95f,
        occupied = false,
        incomingTroops = false,
        coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 4.0)
    )
    private val base = LiveActionCandidate(
        target = ActionTargetSnapshot(
            coordinate = coordinate,
            kind = TargetKind.RESOURCE,
            level = 3,
            actionKind = ActionKind.GATHER,
            point = point
        ),
        observation = observation,
        actionButton = ActionButton(
            kind = ActionKind.GATHER,
            bounds = android.graphics.RectF(890f, 588f, 910f, 612f),
            point = point,
            confidence = 0.95f
        ),
        validation = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT),
        stability = TargetStability(stable = true, consecutiveFrames = 3),
        plannerRank = 0,
        plannerScore = 10.0,
        cameraContinuityValid = true
    )

    @Test
    fun onlyEligibleCandidatesCrossBoundary() {
        val rejected = base.copy(cameraContinuityValid = false)
        val result = LiveActionCandidateReconciler.reconcile(listOf(base, rejected))

        assertEquals(listOf(base), result.eligible)
        assertEquals(
            LiveActionCandidateRejectionReason.CAMERA_CONTINUITY_INVALID,
            result.rejected[rejected]
        )
    }

    @Test
    fun reconciliationPreservesLatestFrameOrderWithoutRanking() {
        val first = base.copy(plannerRank = 0)
        val second = base.copy(
            target = base.target.copy(
                coordinate = WorldCoordinate(355, 168, 512)
            ),
            observation = base.observation.copy(
                coordinate = WorldCoordinate(355, 168, 512)
            ),
            plannerRank = 1
        )

        val result = LiveActionCandidateReconciler.reconcile(listOf(second, first))

        assertEquals(listOf(second, first), result.eligible)
        assertEquals(0, result.rejectedCount)
    }
}
