package com.coolhiman.lordsassistant.data

import android.graphics.Bitmap
import com.coolhiman.lordsassistant.model.TrainingSample
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState
import kotlin.math.max
import kotlin.math.min

/**
 * Converts only authoritative Grid Learning popup results into training data.
 *
 * This recorder is deliberately separate from action authorization: saving a
 * sample can never make a coordinate action-authoritative.
 */
class TrainingSampleRecorder(private val datasetStore: DatasetStore) {

    data class CaptureResult(
        val saved: TrainingSample? = null,
        val rejection: String? = null
    )

    companion object {
        private const val HALF_CROP = 96
    }

    fun record(
        frame: Bitmap?,
        pointX: Float,
        pointY: Float,
        coordinate: WorldCoordinate,
        popup: PopupState,
        acceptedForCalibration: Boolean,
        cameraStable: Boolean,
        coordinateAuthority: String = "OBSERVED"
    ): CaptureResult {
        if (frame == null || frame.isRecycled) return CaptureResult(rejection = "FRAME_UNAVAILABLE")

        val decision = TrainingSamplePolicy.decide(
            popup = popup,
            acceptedForCalibration = acceptedForCalibration,
            cameraStable = cameraStable,
            coordinateAuthority = coordinateAuthority
        )
        if (decision.rejection != null) return CaptureResult(rejection = decision.rejection)
        val label = decision.label ?: return CaptureResult(rejection = "SEMANTIC_LABEL_MISSING")
        val labelType = decision.labelType ?: return CaptureResult(rejection = "SEMANTIC_LABEL_MISSING")
        val confidence = decision.confidence
        val centerX = pointX.toInt().coerceIn(0, frame.width - 1)
        val centerY = pointY.toInt().coerceIn(0, frame.height - 1)
        val left = max(0, centerX - HALF_CROP)
        val top = max(0, centerY - HALF_CROP)
        val right = min(frame.width, centerX + HALF_CROP)
        val bottom = min(frame.height, centerY + HALF_CROP)
        if (right - left < 48 || bottom - top < 48) {
            return CaptureResult(rejection = "CROP_TOO_SMALL")
        }

        val source = when (labelType) {
            "RESOURCE" -> "POPUP_RESOURCE"
            "MONSTER" -> "POPUP_MONSTER"
            "DARKNEST" -> "POPUP_DARKNEST"
            "CASTLE" -> "POPUP_CASTLE"
            "EMPTY" -> "POPUP_EMPTY"
            else -> "POPUP_SEMANTIC"
        }

        val saved = datasetStore.saveCrop(
            source = frame,
            labelType = labelType,
            label = label,
            level = popup.level,
            kingdom = coordinate.kingdom,
            worldX = coordinate.x,
            worldY = coordinate.y,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            captureSource = source,
            confidence = confidence,
            coordinateAuthority = coordinateAuthority,
            cameraStable = cameraStable,
            modelVersion = "popup-v1"
        )
        return CaptureResult(saved = saved)
    }

}
