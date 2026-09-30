package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.TemporalObservationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic multi-frame replay fixtures for scanner state boundaries.
 *
 * These tests intentionally operate on the pure observation/camera state
 * machines instead of Bitmap/OCR implementations, so camera movement,
 * OCR dropout, duplicate detections and quantity changes remain reproducible
 * in CI.
 */
class DeterministicScannerFrameReplayTest {

    private data class ReplayFrame(
        val nowMs: Long,
        val observations: List<MapObservation>
    )

    private fun node(
        id: Int,
        x: Float,
        y: Float,
        quantity: Long = 100_000L,
        label: String? = "WOOD",
        coordinate: WorldCoordinate = WorldCoordinate(355, id, 20)
    ) = MapObservation(
        coordinate = coordinate,
        screenPoint = ScreenPoint(x, y),
        label = label,
        level = 3,
        quantity = quantity,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = 0.90f,
        timestampMs = 0L,
        coordinateConfidence = CoordinateConfidence.observed(
            calibrationUsable = true,
            cameraStable = true,
            residualPx = 4.0
        )
    )

    @Test
    fun replayCameraMotionOcrDropoutDuplicatesAndQuantityChangeFailsClosed() {
        val temporal = TemporalObservationTracker(confirmHits = 2)
        val camera = CameraStateTracker(minSharedTargets = 3, panShiftPx = 70f)

        val a = node(1, 100f, 100f)
        val b = node(2, 200f, 100f)
        val c = node(3, 300f, 100f)

        val replay = listOf(
            ReplayFrame(1000L, listOf(a, b, c)),
            // Same visible scene: establishes camera continuity and temporal confirmation.
            ReplayFrame(1100L, listOf(a, b, c)),
            // OCR dropout for C plus a duplicate A: camera continuity must not
            // be claimed from an incomplete frame, and duplicate A must not add
            // an extra temporal hit.
            ReplayFrame(1200L, listOf(
                a.copy(timestampMs = 1200L),
                a.copy(timestampMs = 1201L, quantity = 125_000L),
                b.copy(timestampMs = 1200L),
                c.copy(timestampMs = 1200L, label = null)
            )),
            // A deliberate pan moves every node by 100 px.
            ReplayFrame(1300L, listOf(
                a.copy(screenPoint = ScreenPoint(200f, 100f), timestampMs = 1300L),
                b.copy(screenPoint = ScreenPoint(300f, 100f), timestampMs = 1300L),
                c.copy(screenPoint = ScreenPoint(400f, 100f), timestampMs = 1300L)
            )),
            // A second stable frame after the pan can re-establish camera
            // continuity, but only after the panning boundary has been seen.
            ReplayFrame(1400L, listOf(
                a.copy(screenPoint = ScreenPoint(200f, 100f), timestampMs = 1400L),
                b.copy(screenPoint = ScreenPoint(300f, 100f), timestampMs = 1400L),
                c.copy(screenPoint = ScreenPoint(400f, 100f), timestampMs = 1400L)
            ))
        )

        val assessments = replay.map { frame ->
            temporal.update(frame.observations, frame.nowMs)
            camera.update(frame.observations)
        }

        assertFalse(assessments[0].continuityForActions)
        assertTrue(assessments[1].continuityForActions)

        // The incomplete/dropout frame cannot provide the three shared targets
        // required for action-grade camera continuity.
        assertFalse(assessments[2].continuityForActions)

        assertEquals(CameraState.PANNING, assessments[3].state)
        assertFalse(assessments[3].continuityForActions)
        assertTrue(assessments[4].continuityForActions)
    }

    @Test
    fun replayQuantityUpdateSurvivesLowerConfidenceAndDuplicateDetection() {
        val temporal = TemporalObservationTracker(confirmHits = 2)
        val first = node(1, 100f, 100f, quantity = 100_000L)
        val updated = first.copy(
            confidence = 0.60f,
            quantity = 850_000L,
            timestampMs = 1100L
        )

        assertEquals(0, temporal.update(listOf(first), 1000L).size)

        val confirmed = temporal.update(
            listOf(
                updated.copy(timestampMs = 1100L),
                updated.copy(timestampMs = 1101L, quantity = 900_000L)
            ),
            1100L
        ).single()

        assertEquals(900_000L, confirmed.quantity)
    }

    @Test
    fun replayOcrIdentityDropoutDoesNotPreserveActionIdentity() {
        val temporal = TemporalObservationTracker(confirmHits = 2)
        val wood = node(1, 100f, 100f, label = "WOOD")
        val dropout = wood.copy(label = null, timestampMs = 1100L)

        assertEquals(0, temporal.update(listOf(wood), 1000L).size)
        assertEquals(0, temporal.update(listOf(dropout), 1100L).size)

        // Re-identification must start a fresh temporal sequence.
        assertEquals(0, temporal.update(listOf(wood.copy(timestampMs = 1200L)), 1200L).size)
        assertEquals(1, temporal.update(listOf(wood.copy(timestampMs = 1300L)), 1300L).size)
    }
}
