package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.UserPreferences

object TargetSelector {
    fun accepts(tile: ResourceTile, preferences: UserPreferences): Boolean =
        preferences.resourceTypes.contains(tile.type) &&
        preferences.resourceLevels.contains(tile.level) &&
        !tile.occupied &&
        !tile.incomingTroops
}
