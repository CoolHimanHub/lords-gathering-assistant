package com.coolhiman.lordsassistant.model

enum class ResourceType { FOOD, STONE, WOOD, ORE, GOLD, GEM, ENERGON }
enum class TargetKind { RESOURCE, MONSTER }

data class WorldCoordinate(val kingdom: Int, val x: Int, val y: Int)

data class ResourceTile(
    val coordinate: WorldCoordinate,
    val type: ResourceType,
    val level: Int,
    val remaining: Long?,
    val occupied: Boolean = false,
    val incomingTroops: Boolean = false,
    val pixelX: Float = 0f,
    val pixelY: Float = 0f
)

data class MonsterTarget(
    val coordinate: WorldCoordinate,
    val name: String,
    val level: Int,
    val pixelX: Float = 0f,
    val pixelY: Float = 0f
)

data class UserPreferences(
    val resourceTypes: Set<ResourceType> = setOf(ResourceType.FOOD, ResourceType.STONE, ResourceType.WOOD, ResourceType.ORE, ResourceType.GOLD),
    val resourceLevels: Set<Int> = setOf(1, 2, 3, 4, 5),
    val monsterLevels: Set<Int> = setOf(1, 2, 3, 4, 5),
    val automaticActions: Boolean = false,
    val overlayEnabled: Boolean = true
)
