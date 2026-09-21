package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileDetectionTest {
    @Test
    fun detectedTileCenterIsDerivedFromBounds() {
        val tile = DetectedTile(
            "FOOD", TileClass.RESOURCE, 5,
            RectF(100f, 50f, 140f, 90f), 0.91
        )
        assertEquals(120f, tile.centerX, 0.001f)
        assertEquals(70f, tile.centerY, 0.001f)
    }

    @Test
    fun detectionFrameSeparatesResourcesAndMonsters() {
        val resource = DetectedTile("WOOD", TileClass.RESOURCE, 4, RectF(0f,0f,10f,10f), 0.9)
        val monster = DetectedTile("Blackwing", TileClass.MONSTER, 3, RectF(20f,0f,30f,10f), 0.9)
        val frame = DetectionFrame(listOf(resource, monster), 12)
        assertEquals(1, frame.resources.size)
        assertEquals(1, frame.monsters.size)
        assertTrue(frame.resources.single().label == "WOOD")
    }
}
