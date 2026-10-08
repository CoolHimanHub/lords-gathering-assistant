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
    fun parsesCompactQuantities() {
        assertEquals(850_000L, GameTextClassifier.classify("Food Lv.5 850K").quantity)
        assertEquals(1_200_000L, GameTextClassifier.classify("Wood Lv.4 1.2M").quantity)
        assertEquals(2_500_000_000L, GameTextClassifier.classify("Gold Lv.5 2.5B").quantity)
    }

    @Test
    fun rejectsMalformedCompactQuantity() {
        assertNull(GameTextClassifier.classify("Food Lv.5 12X").quantity)
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
    fun doesNotTreatForestAsOre() {
        val result = GameTextClassifier.classify("Forest Kingdom of Klinghofen")
        assertNull(result.resource)
        assertNull(result.kind)
    }

    @Test
    fun marksRelayTowerAsNonTarget() {
        val result = GameTextClassifier.classify("Relay Tower 400 Lv.4")
        assertEquals(true, result.ignored)
        assertNull(result.kind)
    }
}
