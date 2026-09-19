package com.coolhiman.lordsassistant

import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.UserPreferences
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.target.TargetRanker
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetRankerTest {
    @Test
    fun rejectsOccupiedAndUnselectedResources() {
        val preferences = UserPreferences(
            resourceTypes = setOf(ResourceType.ORE),
            resourceLevels = setOf(5)
        )
        val candidates = listOf(
            ResourceTile(WorldCoordinate(355, 10, 10), ResourceType.ORE, 5, 1_000_000),
            ResourceTile(WorldCoordinate(355, 11, 10), ResourceType.ORE, 5, 2_000_000, occupied = true),
            ResourceTile(WorldCoordinate(355, 12, 10), ResourceType.GOLD, 5, 5_000_000)
        )

        val ranked = TargetRanker.rank(0, 0, candidates, preferences)
        assertEquals(1, ranked.size)
        assertEquals(10, ranked.first().tile.coordinate.x)
    }
}
