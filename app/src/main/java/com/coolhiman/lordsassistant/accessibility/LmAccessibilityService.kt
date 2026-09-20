package com.coolhiman.lordsassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.target.InteractionGate
import com.coolhiman.lordsassistant.target.TargetValidationResult
import com.coolhiman.lordsassistant.target.ActionButton
import com.coolhiman.lordsassistant.target.ActionTargetSnapshot
import com.coolhiman.lordsassistant.target.PreActionRevalidator
import com.coolhiman.lordsassistant.model.MapObservation

class LmAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: LmAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * The only public gesture entry point. It refuses to dispatch a tap unless
     * the latest target passed the complete interaction gate.
     */
    fun tapValidated(point: ScreenPoint, validation: TargetValidationResult): Boolean {
        if (!InteractionGate.allow(validation, point)) return false
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Revalidates the selected target against the latest scan before allowing
     * a gesture. This is intentionally a separate entry point so future
     * automation cannot accidentally bypass the stale-target check.
     */
    fun tapRevalidated(
        selected: ActionTargetSnapshot,
        latestObservation: MapObservation?,
        latestValidation: TargetValidationResult,
        latestAction: ActionButton?
    ): Boolean {
        val validation = PreActionRevalidator.revalidate(
            selected = selected,
            latestObservation = latestObservation,
            latestValidation = latestValidation,
            latestAction = latestAction
        )
        if (!InteractionGate.allow(validation, latestAction?.point)) return false
        return tapValidated(latestAction!!.point, validation)
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
}
