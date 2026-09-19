package com.coolhiman.lordsassistant.data

import android.content.Context
import com.coolhiman.lordsassistant.model.UserPreferences

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_companion", Context.MODE_PRIVATE)

    fun load() = UserPreferences(
        automaticActions = prefs.getBoolean("auto", false),
        overlayEnabled = prefs.getBoolean("overlay", true)
    )

    fun setAutomaticActions(enabled: Boolean) = prefs.edit().putBoolean("auto", enabled).apply()
    fun setOverlayEnabled(enabled: Boolean) = prefs.edit().putBoolean("overlay", enabled).apply()
}
