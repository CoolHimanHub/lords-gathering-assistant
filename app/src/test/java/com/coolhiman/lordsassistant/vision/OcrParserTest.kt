package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrParserTest {
    @Test
    fun parsesCoordinateWhenXAndYAreSeparateOcrRegions() {
        val regions = listOf(
            TextRegion(RectF(700f, 70f, 760f, 100f), TextClassification(), "X:246"),
            TextRegion(RectF(800f, 70f, 860f, 100f), TextClassification(), "Y:382")
        )

        assertEquals(
            WorldCoordinate(0, 246, 382),
            OcrParser.parseCoordinate("", regions, defaultKingdom = 0)
        )
    }

    @Test
    fun rejectsBadgeContaminatedOutOfRangeCoordinate() {
        assertEquals(
            null,
            OcrParser.parseCoordinate("X:2465 Y:382", defaultKingdom = 0)
        )
    }

    @Test
    fun parsesFlexibleHudPunctuation() {
        assertEquals(
            WorldCoordinate(7, 249, 399),
            OcrParser.parseCoordinate("K-7 X.249 Y=399", defaultKingdom = 0)
        )
    }
}
