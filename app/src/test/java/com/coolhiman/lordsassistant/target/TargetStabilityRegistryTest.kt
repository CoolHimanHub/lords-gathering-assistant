package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetStabilityRegistryTest {
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
    fun cameraBoundaryDropsHistoryEvenWhenTargetWasAbsentDuringDiscontinuity() {
        val registry = TargetStabilityRegistry(requiredFrames = 2)
        val key = "1:200,300:RESOURCE:3"

        assertFalse(registry.update(key, target, CameraState.STABLE).stable)
        assertTrue(registry.update(key, target, CameraState.STABLE).stable)

        // The target is not visible while the camera is moving, so its
        // individual tracker would otherwise never receive the reset signal.
        registry.resetForCameraBoundary()

        assertFalse(registry.update(key, target, CameraState.STABLE).stable)
    }

    @Test
    fun unstableUpdateAlsoResetsRegistryBeforeNextStableFrame() {
        val registry = TargetStabilityRegistry(requiredFrames = 2)
        val key = "1:200,300:RESOURCE:3"

        registry.update(key, target, CameraState.STABLE)
        registry.update(key, target, CameraState.STABLE)
        registry.update(key, target, CameraState.PANNING)
        assertFalse(registry.update(key, target, CameraState.STABLE).stable)
    }
    @Test
    fun repeatedUpdatesWithinOneFrameWouldNotBeStableFromRegistryAlone() {
        val registry = TargetStabilityRegistry(requiredFrames = 2)
        val key = "1:200,300:RESOURCE:3"

        // The scanner must call the registry once per logical target per frame.
        // This test documents the invariant and prevents changing the registry
        // contract into a same-frame frame counter.
        assertFalse(registry.update(key, target, CameraState.STABLE).stable)
        assertTrue(registry.update(key, target, CameraState.STABLE).stable)
    }

}
