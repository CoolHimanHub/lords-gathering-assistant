package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate

class CoordinateResolver(
    private val calibrationStore: CalibrationStore,
    private val kingdom: Int,
    private val maxResidualPx: Double = 35.0
) {
    fun resolve(screenX: Float, screenY: Float): WorldCoordinate? {
        val calibration = calibrationStore.fit(kingdom) ?: return null
        if (!calibration.isUsable(maxResidualPx)) return null
        return calibration.inverse(ScreenPoint(screenX, screenY), kingdom, maxResidualPx)
    }
}
