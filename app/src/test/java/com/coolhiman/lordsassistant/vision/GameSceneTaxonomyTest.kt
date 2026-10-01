package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSceneTaxonomyTest {
    @Test fun mapsFoodToWeakResourceCandidate() {
        val r = GameSceneTaxonomy.classify(AiSceneObject(RectF(), "FOOD", 0.81, null))
        assertEquals(GameSceneClass.RESOURCE_CANDIDATE, r.sceneClass)
    }
    @Test fun mapsPlaceToStructureWithoutClaimingCastle() {
        val r = GameSceneTaxonomy.classify(AiSceneObject(RectF(), "PLACE", 0.92, null))
        assertEquals(GameSceneClass.STRUCTURE_CANDIDATE, r.sceneClass)
    }
    @Test fun keepsMonsterUnknownUntilGameSpecificModel() {
        val r = GameSceneTaxonomy.classify(AiSceneObject(RectF(), "MONSTER", 0.99, null))
        assertEquals(GameSceneClass.UNKNOWN, r.sceneClass)
    }
    @Test fun summaryContainsAllBuckets() {
        val s = GameSceneTaxonomy.summarize(listOf(
            AiSceneObject(RectF(), "FOOD", 0.8, null),
            AiSceneObject(RectF(), "PLACE", 0.8, null),
            AiSceneObject(RectF(), "PLANT", 0.8, null),
            AiSceneObject(RectF(), "MONSTER", 0.8, null)
        ))
        assertTrue(s.contains("resource=1")); assertTrue(s.contains("structure=1"))
        assertTrue(s.contains("terrain=1")); assertTrue(s.contains("unknown=1"))
    }
}
