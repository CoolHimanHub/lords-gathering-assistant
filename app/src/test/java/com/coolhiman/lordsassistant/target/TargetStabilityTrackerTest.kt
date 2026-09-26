package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetStabilityTrackerTest {
    private val target = MapObservation(
        coordinate = WorldCoordinate(1, 200, 300),
        screenPoint = ScreenPoint(120f, 120f),
        label = "WOOD",
        level = 3,
        quantity = null,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = 0.9f,
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
    )

    @Test
    fun repeatedStableFramesBecomeStable() {
        val tracker = TargetStabilityTracker(requiredFrames = 2)
        assertFalse(tracker.update(target, CameraState.STABLE).stable)
        assertTrue(tracker.update(target, CameraState.STABLE).stable)
    }

    @Test
    fun changedTargetResetsConsecutiveCount() {
        val tracker = TargetStabilityTracker(requiredFrames = 2)
        tracker.update(target, CameraState.STABLE)
        val other = target.copy(coordinate = WorldCoordinate(1, 201, 300))
        val result = tracker.update(other, CameraState.STABLE)
        assertFalse(result.sameTarget)
        assertFalse(result.stable)
        assertTrue(result.consecutiveFrames == 1)
    }

    @Test
    fun semanticIdentityChangeResetsStability() {
        val tracker = TargetStabilityTracker(requiredFrames = 2)
        tracker.update(target, CameraState.STABLE)
        val other = target.copy(label = "STONE")
        val result = tracker.update(other, CameraState.STABLE)
        assertFalse(result.sameTarget)
        assertFalse(result.stable)
        assertTrue(result.consecutiveFrames == 1)
    }

    @Test
    fun genericMonsterLabelDoesNotForceIdentityMismatch() {
        val tracker = TargetStabilityTracker(requiredFrames = 2)
        val first = target.copy(kind = TargetKind.MONSTER, label = "MONSTER")
        val second = first.copy(label = "MONSTER")
        tracker.update(first, CameraState.STABLE)
        val result = tracker.update(second, CameraState.STABLE)
        assertTrue(result.sameTarget)
        assertTrue(result.stable)
    }

    @Test
    fun unstableCameraResetsTargetStability() {
        val tracker = TargetStabilityTracker(requiredFrames = 2)
        tracker.update(target, CameraState.STABLE)
        val result = tracker.update(target, CameraState.PANNING)
        assertFalse(result.stable)
        assertTrue(result.consecutiveFrames == 0)
        assertFalse(result.sameTarget)
    }
}
