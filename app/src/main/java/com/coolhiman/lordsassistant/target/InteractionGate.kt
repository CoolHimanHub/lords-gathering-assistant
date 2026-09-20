package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint

/**
 * Final safety gate for any future Accessibility interaction.
 *
 * This layer never enables automation by itself. A gesture is allowed only when
 * the latest target validation is safe and a concrete interaction point exists.
 */
object InteractionGate {
    fun allow(validation: TargetValidationResult, point: ScreenPoint?): Boolean =
        validation.safe && validation.stage == TargetValidationStage.SAFE_TO_INTERACT && point != null
}
