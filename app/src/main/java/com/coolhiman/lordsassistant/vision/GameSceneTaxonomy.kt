package com.coolhiman.lordsassistant.vision

import android.graphics.RectF

enum class GameSceneClass { RESOURCE_CANDIDATE, STRUCTURE_CANDIDATE, TERRAIN_CANDIDATE, UNKNOWN }

data class GameSceneHypothesis(
    val bounds: RectF,
    val sceneClass: GameSceneClass,
    val confidence: Double,
    val sourceCategory: String?
)

/** Conservative bridge from generic ML Kit labels to game-facing hypotheses. */
object GameSceneTaxonomy {
    fun classify(objectDetection: AiSceneObject): GameSceneHypothesis {
        val sceneClass = when (objectDetection.category) {
            "FOOD" -> GameSceneClass.RESOURCE_CANDIDATE
            "PLACE", "HOME_GOOD" -> GameSceneClass.STRUCTURE_CANDIDATE
            "PLANT" -> GameSceneClass.TERRAIN_CANDIDATE
            else -> GameSceneClass.UNKNOWN
        }
        return GameSceneHypothesis(objectDetection.bounds, sceneClass, objectDetection.confidence, objectDetection.category)
    }

    fun summarize(objects: List<AiSceneObject>): String {
        val counts = objects.map(::classify).groupingBy { it.sceneClass }.eachCount()
        return listOf(
            GameSceneClass.RESOURCE_CANDIDATE,
            GameSceneClass.STRUCTURE_CANDIDATE,
            GameSceneClass.TERRAIN_CANDIDATE,
            GameSceneClass.UNKNOWN
        ).joinToString("/") { "${it.name.removeSuffix("_CANDIDATE").lowercase()}=${counts[it] ?: 0}" }
    }
}
