package com.coolhiman.lordsassistant.data

import android.content.Context
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.UserPreferences

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_companion", Context.MODE_PRIVATE)
    fun load() = UserPreferences(
        resourceTypes = ResourceType.values().filter {
            prefs.getBoolean("resource_${it.name}", it !in setOf(ResourceType.GEM, ResourceType.ENERGON))
        }.toSet(),
        resourceLevels = (1..5).filter { prefs.getBoolean("resource_level_$it", true) }.toSet(),
        automaticActions = prefs.getBoolean("auto", false),
        overlayEnabled = prefs.getBoolean("overlay", true)
    )
    fun setAutomaticActions(enabled: Boolean) = prefs.edit().putBoolean("auto", enabled).apply()
    fun setOverlayEnabled(enabled: Boolean) = prefs.edit().putBoolean("overlay", enabled).apply()
    fun setResourceEnabled(type: ResourceType, enabled: Boolean) =
        prefs.edit().putBoolean("resource_${type.name}", enabled).apply()
    fun setResourceLevelEnabled(level: Int, enabled: Boolean) =
        prefs.edit().putBoolean("resource_level_$level", enabled).apply()
}
