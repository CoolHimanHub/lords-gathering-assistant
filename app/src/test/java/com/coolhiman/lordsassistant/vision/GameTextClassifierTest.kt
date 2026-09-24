package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTextClassifierTest {
    @Test
    fun classifiesNamedResourceAndQuantity() {
        val result = GameTextClassifier.classify("Woods Lv.3 1,240,000 Unoccupied")
        assertEquals(TargetKind.RESOURCE, result.kind)
        assertEquals(ResourceType.WOOD, result.resource)
        assertEquals(3, result.level)
        assertEquals(1_240_000L, result.quantity)
        assertEquals(false, result.occupied)
    }

    @Test
    fun classifiesNamedMonster() {
        val result = GameTextClassifier.classify("Frostwing Lv.4")
        assertEquals(TargetKind.MONSTER, result.kind)
        assertEquals("Frostwing", result.monsterName)
        assertEquals(4, result.level)
        assertNull(result.resource)
    }
    @Test
    fun marksRelayTowerAsNonTarget() {
        val result = GameTextClassifier.classify("Relay Tower 400 Lv.4")
        assertEquals(true, result.ignored)
        assertNull(result.kind)
    }
}
