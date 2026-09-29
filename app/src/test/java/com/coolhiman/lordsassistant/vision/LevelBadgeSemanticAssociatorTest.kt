package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelBadgeSemanticAssociatorTest {
    private val associator = LevelBadgeSemanticAssociator()

    private fun levelRegion(level: Int, x: Float, y: Float): TextRegion =
        TextRegion(
            RectF(x, y, x + 12f, y + 12f),
            TextClassification(kind = TargetKind.RESOURCE, level = level),
            "L$level"
        )

    @Test
    fun choosesClearlyNearestLevel() {
        val badge = RectF(100f, 100f, 120f, 120f)
        val result = associator.associate(
            badge,
            listOf(
                levelRegion(4, 105f, 104f),
                levelRegion(2, 145f, 145f)
            )
        )
        assertEquals(4, result)
    }

    @Test
    fun rejectsSimilarlySpacedNearbyLevels() {
        val badge = RectF(100f, 100f, 120f, 120f)
        val result = associator.associate(
            badge,
            listOf(
                levelRegion(4, 120f, 104f),
                levelRegion(2, 121f, 104f)
            )
        )
        assertNull(result)
    }

    @Test
    fun ignoresOutOfRangeLevels() {
        val badge = RectF(100f, 100f, 120f, 120f)
        val result = associator.associate(
            badge,
            listOf(levelRegion(6, 102f, 102f))
        )
        assertNull(result)
    }
}
