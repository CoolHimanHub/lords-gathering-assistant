package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.LiveActionCandidate
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveActionCandidatePolicyTest {
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
    private val validation = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )
    private val candidate = LiveActionCandidate(
        target = ActionTargetSnapshot(
            coordinate = coordinate,
            kind = TargetKind.RESOURCE,
            level = 3,
            actionKind = ActionKind.GATHER,
            point = point,
            semanticIdentity = "WOOD"
        ),
        observation = observation,
        actionButton = ActionButton(
            kind = ActionKind.GATHER,
            bounds = android.graphics.RectF(890f, 588f, 910f, 612f),
            point = point,
            confidence = 0.95f
        ),
        validation = validation,
        stability = TargetStability(stable = true, consecutiveFrames = 3),
        plannerRank = 0,
        plannerScore = 10.0,
        cameraContinuityValid = true
    )

    @Test
    fun rankedFiniteCandidateIsSchedulerEligible() {
        assertTrue(LiveActionCandidatePolicy.isSchedulerEligible(candidate))
    }

    @Test
    fun missingSemanticIdentityCannotCrossSchedulerBoundary() {
        val missing = candidate.copy(
            target = candidate.target.copy(semanticIdentity = null)
        )

        assertFalse(LiveActionCandidatePolicy.isSchedulerEligible(missing))
        assertEquals(
            LiveActionCandidateRejectionReason.SEMANTIC_IDENTITY_MISSING,
            LiveActionCandidatePolicy.rejectionReason(missing)
        )
    }

    @Test
    fun genericMonsterIdentityCannotBeUsedForAction() {
        val monster = candidate.copy(
            observation = observation.copy(
                kind = TargetKind.MONSTER,
                label = "MONSTER"
            ),
            target = candidate.target.copy(
                kind = TargetKind.MONSTER,
                semanticIdentity = null
            )
        )

        assertFalse(LiveActionCandidatePolicy.isSchedulerEligible(monster))
    }

    @Test
    fun unrankedCandidateIsDiagnosticOnly() {
        assertFalse(
            LiveActionCandidatePolicy.isSchedulerEligible(
                candidate.copy(
                    plannerRank = Int.MAX_VALUE,
                    plannerScore = Double.NEGATIVE_INFINITY
                )
            )
        )
    }

    @Test
    fun nonFinitePlannerScoreCannotCrossSchedulerBoundary() {
        assertFalse(
            LiveActionCandidatePolicy.isSchedulerEligible(
                candidate.copy(plannerScore = Double.POSITIVE_INFINITY)
            )
        )
    }

    @Test
    fun negativePlannerRankCannotCrossSchedulerBoundary() {
        assertFalse(
            LiveActionCandidatePolicy.isSchedulerEligible(
                candidate.copy(plannerRank = -1)
            )
        )
    }

    @Test
    fun unsafeCandidateCannotCrossSchedulerBoundary() {
        assertFalse(
            LiveActionCandidatePolicy.isSchedulerEligible(
                candidate.copy(
                    validation = TargetValidationResult(
                        safe = false,
                        stage = TargetValidationStage.DETECTED
                    )
                )
            )
        )
    }

    @Test
    fun missingCameraContinuityCannotCrossSchedulerBoundary() {
        assertFalse(
            LiveActionCandidatePolicy.isSchedulerEligible(
                candidate.copy(cameraContinuityValid = false)
            )
        )
    }

    @Test
    fun calibratedCoordinateCannotCrossSchedulerBoundary() {
        val calibrated = candidate.copy(
            observation = observation.copy(
                coordinateConfidence = CoordinateConfidence.calibrated(true, true, 4.0)
            )
        )
        assertFalse(LiveActionCandidatePolicy.isSchedulerEligible(calibrated))
        assertEquals(
            LiveActionCandidateRejectionReason.COORDINATE_NOT_ACTION_AUTHORITATIVE,
            LiveActionCandidatePolicy.rejectionReason(calibrated)
        )
    }

    @Test
    fun observedCoordinateStillRequiresStableCameraAndUsableCalibration() {
        val notUsable = candidate.copy(
            observation = observation.copy(
                coordinateConfidence = CoordinateConfidence.observed(false, true)
            )
        )
        assertFalse(LiveActionCandidatePolicy.isSchedulerEligible(notUsable))
        assertEquals(
            LiveActionCandidateRejectionReason.COORDINATE_NOT_ACTION_AUTHORITATIVE,
            LiveActionCandidatePolicy.rejectionReason(notUsable)
        )
    }

}