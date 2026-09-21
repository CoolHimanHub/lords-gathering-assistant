package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.vision.TextClassification
import com.coolhiman.lordsassistant.vision.TextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionButtonDetectorTest {
    @Test
    fun gatherTextBecomesResourceActionWhenPopupAnchorIsNear() {
        val buttons = ActionButtonDetector.detect(
            listOf(
                TextRegion(RectF(100f, 400f, 220f, 430f), TextClassification(), "Woods Lv.3"),
                TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Gather")
            ),
            popupPresent = true,
            targetKind = TargetKind.RESOURCE
        )
        assertEquals(1, buttons.size)
        assertEquals(ActionKind.GATHER, buttons.first().kind)
        assertTrue(buttons.first().point.x > 100f)
    }

    @Test
    fun huntDoesNotBecomeGatherForResource() {
        val buttons = ActionButtonDetector.detect(
            listOf(
                TextRegion(RectF(100f, 400f, 220f, 430f), TextClassification(), "Woods Lv.3"),
                TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Hunt")
            ),
            popupPresent = true,
            targetKind = TargetKind.RESOURCE
        )
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun missingPopupBlocksActionDetection() {
        val buttons = ActionButtonDetector.detect(
            listOf(
                TextRegion(RectF(100f, 400f, 220f, 430f), TextClassification(), "Woods Lv.3"),
                TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Gather")
            ),
            popupPresent = false,
            targetKind = TargetKind.RESOURCE
        )
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun distantActionTextIsNotAssociatedWithPopup() {
        val buttons = ActionButtonDetector.detect(
            listOf(
                TextRegion(RectF(100f, 400f, 220f, 430f), TextClassification(), "Woods Lv.3"),
                TextRegion(RectF(1200f, 900f, 1280f, 930f), TextClassification(), "Gather")
            ),
            popupPresent = true,
            targetKind = TargetKind.RESOURCE
        )
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun actionCanAssociateWithOccupierAnchor() {
        val buttons = ActionButtonDetector.detect(
            listOf(
                TextRegion(RectF(900f, 300f, 980f, 330f), TextClassification(), "Unoccupied"),
                TextRegion(RectF(900f, 360f, 980f, 390f), TextClassification(), "Gather")
            ),
            popupPresent = true,
            targetKind = TargetKind.RESOURCE
        )
        assertEquals(1, buttons.size)
        assertEquals(ActionKind.GATHER, buttons.first().kind)
    }
}
