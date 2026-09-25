package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate

data class CoordinateResolution(
    val coordinate: WorldCoordinate,
    val confidence: CoordinateConfidence
)

class CoordinateResolver(
    private val calibrationStore: CalibrationStore,
    private val kingdom: Int,
    private val maxResidualPx: Double = 35.0
) {
    fun resolve(screenX: Float, screenY: Float): WorldCoordinate? =
        resolve(ScreenPoint(screenX, screenY), null)

    fun resolve(screen: ScreenPoint, cameraModel: CameraModel?): WorldCoordinate? =
        resolveDetailed(screen, cameraModel)?.coordinate

    /**
     * Resolve a coordinate together with the evidence used to obtain it.
     * A global OCR K/X/Y value never upgrades unrelated screen targets.
     */
    fun resolveDetailed(screen: ScreenPoint, cameraModel: CameraModel?): CoordinateResolution? {
        val calibration = calibrationStore.fit(kingdom) ?: return null
        if (!calibration.isUsable(maxResidualPx)) return null

        val coordinate = if (cameraModel != null) {
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
        } ?: return null

        return CoordinateResolution(
            coordinate,
            CoordinateConfidence.calibrated(
                calibrationUsable = true,
                cameraStable = cameraModel == null || cameraModel.isUsable(maxResidualPx),
                residualPx = calibration.rmsErrorPx
            )
        )
    }
}
