package com.coolhiman.lordsassistant.map

import android.content.Context
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.json.JSONArray
import org.json.JSONObject

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("map_calibration", Context.MODE_PRIVATE)
    private val key = "samples"

    fun addSample(world: WorldCoordinate, screen: ScreenPoint) {
        val array = loadArray()
        array.put(JSONObject().apply {
            put("kingdom", world.kingdom); put("x", world.x); put("y", world.y)
            put("screenX", screen.x); put("screenY", screen.y)
        })
        while (array.length() > 32) array.remove(0)
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun sampleCount(): Int = loadArray().length()

    fun clear() { prefs.edit().remove(key).apply() }

    fun fit(kingdom: Int? = null): Calibration? {
        val calibrator = AffineGridCalibrator()
        val array = loadArray()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (kingdom != null && o.getInt("kingdom") != kingdom) continue
            calibrator.addSample(
                WorldCoordinate(o.getInt("kingdom"), o.getInt("x"), o.getInt("y")),
                ScreenPoint(o.getDouble("screenX").toFloat(), o.getDouble("screenY").toFloat())
            )
        }
        return calibrator.fit()
    }

    private fun loadArray(): JSONArray = JSONArray(prefs.getString(key, "[]"))
}