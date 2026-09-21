package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAnchorTrackerTest {

    private fun observation(
        x: Float,
        y: Float,
        worldX: Int,
        label: String = "WOOD"
    ) = MapObservation(
        coordinate = WorldCoordinate(1, worldX, 100),
        screenPoint = ScreenPoint(x, y),
        label = label,
        level = 3,
        quantity = 1000L,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = 0.95f
    )

    @Test
    fun sharedUniqueSemanticTargetsBecomeCameraAnchors() {
        val tracker = CameraAnchorTracker()

        tracker.update(
            listOf(
                observation(100f, 100f, 10),
                observation(200f, 100f, 20)
            )
        )

        val anchors = tracker.update(
            listOf(
                observation(130f, 120f, 10),
                observation(230f, 120f, 20)
            )
        )

        assertEquals(2, anchors.size)
        assertEquals(WorldCoordinate(1, 10, 100), anchors[0].world)
        assertEquals(ScreenPoint(130f, 120f), anchors[0].screen)
    }

    @Test
    fun repeatedSemanticTargetsAreMatchedOneToOne() {
        val tracker = CameraAnchorTracker()

        tracker.update(
            listOf(
                observation(100f, 100f, 10),
                observation(200f, 100f, 20)
            )
        )

        val anchors = tracker.update(
            listOf(
                observation(130f, 120f, 10),
                observation(230f, 120f, 20)
            )
        )

        assertEquals(2, anchors.size)
        assertEquals(WorldCoordinate(1, 10, 100), anchors[0].world)
        assertEquals(WorldCoordinate(1, 20, 100), anchors[1].world)
    }

    @Test
    fun ambiguousRepeatedTargetsAreNotAssociated() {
        val tracker = CameraAnchorTracker(ambiguityMarginPx = 20f)

        tracker.update(
            listOf(
                observation(100f, 100f, 10),
                observation(140f, 100f, 20)
            )
        )

        val anchors = tracker.update(
            listOf(
                observation(119f, 100f, 10),
                observation(121f, 100f, 20)
            )
        )

        assertTrue(anchors.isEmpty())
    }

    @Test
    fun farSemanticMovementIsNotAssociated() {
        val tracker = CameraAnchorTracker(maxAssociationDistancePx = 50f)

        tracker.update(listOf(observation(100f, 100f, 10)))
        val anchors = tracker.update(listOf(observation(300f, 300f, 10)))

        assertTrue(anchors.isEmpty())
    }

    @Test
    fun resetBreaksCrossFrameAnchorAssociation() {
        val tracker = CameraAnchorTracker()

        tracker.update(listOf(observation(100f, 100f, 10)))
        tracker.reset()

        assertTrue(
            tracker.update(listOf(observation(130f, 120f, 10))).isEmpty()
        )
    }
}
