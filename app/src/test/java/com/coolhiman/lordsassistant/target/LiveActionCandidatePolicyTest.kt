package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.LiveActionCandidate
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
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
        incomingTroops = false
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
            point = point
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
        plannerScore = 10.0
    )

    @Test
    fun rankedFiniteCandidateIsSchedulerEligible() {
        assertTrue(LiveActionCandidatePolicy.isSchedulerEligible(candidate))
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
}
