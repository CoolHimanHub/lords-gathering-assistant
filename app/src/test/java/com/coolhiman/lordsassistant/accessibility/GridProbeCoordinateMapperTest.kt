package com.coolhiman.lordsassistant.accessibility

import com.coolhiman.lordsassistant.model.ScreenPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridProbeCoordinateMapperTest {
    @Test
    fun mapsSameSizedDisplayWithoutChangingPoint() {
        val point = GridProbeCoordinateMapper.map(
            ScreenPoint(400f, 700f),
            1080,
            2400,
            1080,
            2400
        )
        assertEquals(400f, point?.x ?: -1f, 0.001f)
        assertEquals(700f, point?.y ?: -1f, 0.001f)
    }

    @Test
    fun scalesCoordinatesWhenDisplayResolutionDiffersButAspectMatches() {
        val point = GridProbeCoordinateMapper.map(
            ScreenPoint(500f, 1000f),
            1000,
            2000,
            1500,
            3000
        )
        assertEquals(750f, point?.x ?: -1f, 0.001f)
        assertEquals(1500f, point?.y ?: -1f, 0.001f)
    }

    @Test
    fun rejectsLikelyRotationOrCropMismatch() {
        val point = GridProbeCoordinateMapper.map(
            ScreenPoint(500f, 1000f),
            1000,
            2000,
            2000,
            1000
        )
        assertNull(point)
    }

    @Test
    fun rejectsPointOutsideSourceDisplayBeforeScaling() {
        assertNull(
            GridProbeCoordinateMapper.map(
                ScreenPoint(1001f, 1000f),
                1000,
                2000,
                1500,
                3000
            )
        )
    }

    @Test
    fun checksMappedPointAgainstDisplayBounds() {
        assertEquals(
            true,
            GridProbeCoordinateMapper.isInsideDisplay(ScreenPoint(10f, 20f), 100, 100)
        )
        assertEquals(
            false,
            GridProbeCoordinateMapper.isInsideDisplay(ScreenPoint(100f, 20f), 100, 100)
        )
    }
}
