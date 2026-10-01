package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LordsMobileSceneModelContractTest {
    @Test fun supportsRequiredGameObjectClasses() {
        assertEquals(
            setOf(
                LordsMobileObjectClass.RESOURCE,
                LordsMobileObjectClass.MONSTER,
                LordsMobileObjectClass.DARKNEST,
                LordsMobileObjectClass.CASTLE,
                LordsMobileObjectClass.EMPTY,
                LordsMobileObjectClass.UNKNOWN
            ),
            LordsMobileObjectClass.values().toSet()
        )
    }

    @Test fun resourceHypothesisCanCarryTypeAndLevel() {
        val h = LordsMobileObjectHypothesis(
            bounds = RectF(10f, 20f, 30f, 40f),
            objectClass = LordsMobileObjectClass.RESOURCE,
            confidence = 0.93,
            resourceType = LordsMobileResourceType.ORE,
            level = 3,
            levelConfidence = 0.88
        )
        assertTrue(h.isGameSpecific)
        assertEquals(LordsMobileResourceType.ORE, h.resourceType)
        assertEquals(3, h.level)
        assertFalse(h.isActionSafeEvidence)
    }

    @Test fun unknownNeverBecomesActionSafeEvidence() {
        val h = LordsMobileObjectHypothesis(RectF(), LordsMobileObjectClass.UNKNOWN, 0.99)
        assertFalse(h.isGameSpecific)
        assertFalse(h.isActionSafeEvidence)
    }
}
