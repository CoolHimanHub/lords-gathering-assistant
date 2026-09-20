package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionFusionTest {
    @Test
    fun marchNearTileMarksItOccupiedAndIncoming() {
        val tile = DetectedTile(
            "FOOD", TileClass.RESOURCE, 5, RectF(100f,100f,140f,140f), 0.9
        )
        val fusion = DetectionFusion(maxMarchDistancePx = 80f)
        val result = fusion.fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(155f, 120f, 50.0, 0.8f))
        ) { _, _ -> WorldCoordinate(1, 200, 300) }

        assertEquals(1, result.size)
        assertTrue(result.single().occupied == true)
        assertTrue(result.single().incomingTroops == true)
        assertEquals(200, result.single().coordinate?.x)
    }

    @Test
    fun popupUnoccupiedOverridesMarchForSelectedCoordinate() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            quantity = 720000L,
            occupied = false,
            incomingTroops = false,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )
        val result = DetectionFusion(maxMarchDistancePx = 80f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(155f, 120f, 50.0, 0.8f)),
            { _, _ -> WorldCoordinate(1, 200, 300) },
            popup
        )
        assertEquals(false, result.single().occupied)
        assertEquals(false, result.single().incomingTroops)
        assertEquals(720000L, result.single().classification.quantity)
    }

    @Test
    fun distantMarchLeavesOccupancyUnknownWhenNoOtherEvidenceExists() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion(maxMarchDistancePx = 30f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(300f, 300f, 50.0, 0.8f))
        ) { _, _ -> null }

        assertNull(result.single().occupied)
        assertNull(result.single().incomingTroops)
    }
    @Test
    fun ambiguousNearbyMarchesDoNotForceIncomingState() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion(maxMarchDistancePx = 80f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(
                MarchSignal(155f, 120f, 50.0, 0.95f),
                MarchSignal(120f, 155f, 50.0, 0.90f)
            )
        ) { _, _ -> WorldCoordinate(1, 200, 300) }

        assertNull(result.single().occupied)
        assertNull(result.single().incomingTroops)
    }

}

