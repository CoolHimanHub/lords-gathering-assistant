package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupStateParserTest {
    @Test
    fun parsesResourcePopupAndExactCoordinate() {
        val state = PopupStateParser.parse(
            "Woods Lv.3 Timber 720,000 Occupier Unoccupied Gather K:355 X:167 Y:511",
            0
        )
        assertTrue(state.isPopup)
        assertEquals(TargetKind.RESOURCE, state.kind)
        assertEquals(ResourceType.WOOD, state.resource)
        assertEquals(3, state.level)
        assertEquals(720000L, state.quantity)
        assertEquals(false, state.occupied)
        assertEquals(355, state.coordinate?.kingdom)
        assertEquals(167, state.coordinate?.x)
        assertEquals(511, state.coordinate?.y)
    }

    @Test
    fun occupiedPopupIsMarkedOccupied() {
        val state = PopupStateParser.parse(
            "Stone Lv.4 Stone 450,000 Occupier Gathering K:355 X:10 Y:20",
            0
        )
        assertEquals(true, state.occupied)
    }
}
