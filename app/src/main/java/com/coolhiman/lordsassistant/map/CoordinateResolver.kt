package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate

class CoordinateResolver(
    private val calibrationStore: CalibrationStore,
    private val kingdom: Int,
    private val maxResidualPx: Double = 35.0
) {
    fun resolve(screenX: Float, screenY: Float): WorldCoordinate? =
        resolve(ScreenPoint(screenX, screenY), null)

    fun resolve(
        screen: ScreenPoint,
        cameraModel: CameraModel?
    ): WorldCoordinate? {
        val calibration = calibrationStore.fit(kingdom) ?: return null
        if (!calibration.isUsable(maxResidualPx)) return null

        return if (cameraModel != null) {
            CameraInvariantWorldModel(
                baseCalibration = calibration,
                maxAnchorResidualPx = maxResidualPx
            ).resolve(
                screen = screen,
                kingdom = kingdom,
                model = cameraModel,
                maxResidualPx = maxResidualPx
            )
        } else {
            calibration.inverse(screen, kingdom, maxResidualPx)
        }
    }
}
