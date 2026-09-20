package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.vision.TextRegion
import com.coolhiman.lordsassistant.vision.TextClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionButtonDetectorTest {
    @Test
    fun gatherTextBecomesResourceAction() {
        val buttons = ActionButtonDetector.detect(
            listOf(TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Gather")),
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
            listOf(TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Hunt")),
            popupPresent = true,
            targetKind = TargetKind.RESOURCE
        )
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun missingPopupBlocksActionDetection() {
        val buttons = ActionButtonDetector.detect(
            listOf(TextRegion(RectF(100f, 500f, 180f, 530f), TextClassification(), "Gather")),
            popupPresent = false,
            targetKind = TargetKind.RESOURCE
        )
        assertTrue(buttons.isEmpty())
    }
}
