package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraInvariantWorldModelTest {

    private val calibration = Calibration(
        screenX = doubleArrayOf(2.0, 0.5, 100.0),
        screenY = doubleArrayOf(-0.25, 3.0, 200.0),
        rmsErrorPx = 0.0
    )

    private val model = CameraInvariantWorldModel(calibration)

    private fun world(x: Int, y: Int) = WorldCoordinate(1, x, y)

    private fun currentScreen(x: Int, y: Int): ScreenPoint {
        val base = calibration.predict(world(x, y))
        return ScreenPoint(
            base.x * 1.5f + 50f,
            base.y * 1.5f - 30f
        )
    }

    @Test
    fun uniformPanAndZoomAreRecoveredWithoutRefittingWorldGeometry() {
        val anchors = listOf(
            world(0, 0),
            world(10, 0),
            world(0, 10),
            world(10, 10)
        ).map { coordinate ->
            CameraWorldAnchor(coordinate, currentScreen(coordinate.x, coordinate.y))
        }

        val fitted = model.fit(anchors)

        assertNotNull(fitted)
        assertEquals(1.5, fitted!!.scale, 1e-6)
        assertEquals(50.0, fitted.offsetX, 1e-5)
        assertEquals(-30.0, fitted.offsetY, 1e-5)
        assertTrue(fitted.residualRmsPx < 1e-4)

        val resolved = model.resolve(
            screen = currentScreen(7, 4),
            kingdom = 1,
            model = fitted
        )

        assertEquals(world(7, 4), resolved)
    }

    @Test
    fun panAndZoomAcrossFramesPreserveWorldIdentity() {
        val anchors = listOf(
            world(0, 0),
            world(10, 0),
            world(0, 10),
            world(10, 10)
        )
        val firstFrame = anchors.map { coordinate ->
            CameraWorldAnchor(coordinate, calibration.predict(coordinate))
        }
        val secondFrame = anchors.map { coordinate ->
            val base = calibration.predict(coordinate)
            CameraWorldAnchor(
                coordinate,
                ScreenPoint(base.x * 1.25f + 80f, base.y * 1.25f - 45f)
            )
        }

        val firstModel = model.fit(firstFrame)
        val secondModel = model.fit(secondFrame)

        assertNotNull(firstModel)
        assertNotNull(secondModel)
        assertEquals(1.25, secondModel!!.scale, 1e-6)
        assertEquals(80.0, secondModel.offsetX, 1e-5)
        assertEquals(-45.0, secondModel.offsetY, 1e-5)

        val targetBase = calibration.predict(world(7, 4))
        val targetScreen = ScreenPoint(
            targetBase.x * 1.25f + 80f,
            targetBase.y * 1.25f - 45f
        )
        assertEquals(
            world(7, 4),
            model.resolve(targetScreen, 1, secondModel)
        )
    }

    @Test
    fun rotatedFrameDoesNotBecomeAValidPanZoomModel() {
        val anchors = listOf(
            world(0, 0),
            world(10, 0),
            world(0, 10),
            world(10, 10)
        ).map { coordinate ->
            val base = calibration.predict(coordinate)
            val dx = base.x - 110f
            val dy = base.y - 215f
            CameraWorldAnchor(
                coordinate,
                ScreenPoint(
                    110f - dy,
                    215f + dx
                )
            )
        }

        assertNull(model.fit(anchors))
    }

    @Test
    fun inconsistentAnchorGeometryFailsClosed() {
        val anchors = listOf(
            CameraWorldAnchor(world(0, 0), currentScreen(0, 0)),
            CameraWorldAnchor(
                world(10, 0),
                ScreenPoint(
                    currentScreen(10, 0).x + 100f,
                    currentScreen(10, 0).y
                )
            ),
            CameraWorldAnchor(world(0, 10), currentScreen(0, 10))
        )

        val fitted = model.fit(anchors)

        assertNull(fitted)
    }

    @Test
    fun twoAnchorsCannotOpenCameraModel() {
        val fitted = model.fit(
            listOf(
                CameraWorldAnchor(world(0, 0), currentScreen(0, 0)),
                CameraWorldAnchor(world(10, 0), currentScreen(10, 0))
            )
        )

        assertNull(fitted)
    }

    @Test
    fun collinearAnchorsCannotOpenCameraModel() {
        val fitted = model.fit(
            listOf(
                CameraWorldAnchor(world(0, 0), currentScreen(0, 0)),
                CameraWorldAnchor(world(10, 0), currentScreen(10, 0)),
                CameraWorldAnchor(world(20, 0), currentScreen(20, 0))
            )
        )

        assertNull(fitted)
    }

    @Test
    fun tooFewAnchorsCannotOpenCameraModel() {
        val fitted = model.fit(
            listOf(
                CameraWorldAnchor(world(0, 0), currentScreen(0, 0))
            )
        )

        assertNull(fitted)
    }
}
