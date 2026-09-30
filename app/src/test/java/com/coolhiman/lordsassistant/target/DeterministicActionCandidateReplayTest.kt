package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.LiveActionCandidate
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic replay of the scanner -> live-action eligibility boundary.
 *
 * The replay deliberately changes one safety-critical property per frame.
 * A candidate may recover only when the latest frame independently satisfies
 * every live eligibility requirement.
 */
class DeterministicActionCandidateReplayTest {

    private data class ReplayFrame(
        val candidate: LiveActionCandidate
    )

    private val coordinate = WorldCoordinate(355, 167, 511)
    private val point = ScreenPoint(900f, 600f)

    private fun candidate(
        cameraContinuityValid: Boolean = true,
        coordinateAuthoritative: Boolean = true,
        semanticIdentity: String? = "WOOD",
        safe: Boolean = true,
        stage: TargetValidationStage = TargetValidationStage.SAFE_TO_INTERACT,
        plannerRank: Int = 0,
        plannerScore: Double = 10.0
    ): LiveActionCandidate {
        val observation = MapObservation(
            coordinate = coordinate,
            kind = TargetKind.RESOURCE,
            level = 3,
            screenPoint = point,
            label = semanticIdentity,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false,
            coordinateConfidence = if (coordinateAuthoritative) {
                CoordinateConfidence.observed(true, true, residualPx = 4.0)
            } else {
                CoordinateConfidence.none()
            }
        )

        return LiveActionCandidate(
            target = ActionTargetSnapshot(
                coordinate = coordinate,
                kind = TargetKind.RESOURCE,
                level = 3,
                actionKind = ActionKind.GATHER,
                point = point,
                semanticIdentity = semanticIdentity
            ),
            observation = observation,
            actionButton = ActionButton(
                kind = ActionKind.GATHER,
                bounds = android.graphics.RectF(890f, 588f, 910f, 612f),
                point = point,
                confidence = 0.95f
            ),
            validation = TargetValidationResult(safe, stage),
            stability = TargetStability(stable = true, consecutiveFrames = 2),
            plannerRank = plannerRank,
            plannerScore = plannerScore,
            cameraContinuityValid = cameraContinuityValid
        )
    }

    @Test
    fun replaySafetyBoundaryRejectsDropoutCameraLossAndOnlyThenRecovers() {
        val replay = listOf(
            ReplayFrame(candidate()),
            // OCR/semantic dropout: the target remains visible but no canonical
            // action identity is available, so it must leave the eligible set.
            ReplayFrame(candidate(semanticIdentity = null)),
            // Camera boundary: even a fully identified target is not actionable
            // while continuity is invalid.
            ReplayFrame(candidate(cameraContinuityValid = false)),
            // Fresh frame restores all independent evidence.
            ReplayFrame(candidate())
        )

        val decisions = replay.map { frame ->
            LiveActionCandidateReconciler.reconcile(listOf(frame.candidate))
        }

        assertEquals(1, decisions[0].eligible.size)
        assertEquals(0, decisions[0].rejectedCount)

        assertTrue(decisions[1].eligible.isEmpty())
        assertEquals(
            LiveActionCandidateRejectionReason.SEMANTIC_IDENTITY_MISSING,
            decisions[1].rejected.values.single()
        )

        assertTrue(decisions[2].eligible.isEmpty())
        assertEquals(
            LiveActionCandidateRejectionReason.CAMERA_CONTINUITY_INVALID,
            decisions[2].rejected.values.single()
        )

        assertEquals(1, decisions[3].eligible.size)
        assertEquals(0, decisions[3].rejectedCount)
    }

    @Test
    fun replayInvalidValidationNeverRecoversFromPriorEligibleFrame() {
        val replay = listOf(
            candidate(),
            candidate(
                safe = false,
                stage = TargetValidationStage.DETECTED
            ),
            candidate()
        )

        val decisions = replay.map {
            LiveActionCandidateReconciler.reconcile(listOf(it.candidate))
        }

        assertEquals(1, decisions[0].eligible.size)
        assertEquals(
            LiveActionCandidateRejectionReason.VALIDATION_UNSAFE,
            decisions[1].rejected.values.single()
        )
        assertEquals(1, decisions[2].eligible.size)
    }
}
