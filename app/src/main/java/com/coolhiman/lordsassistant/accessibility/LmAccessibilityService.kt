package com.coolhiman.lordsassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.target.InteractionGate
import com.coolhiman.lordsassistant.target.TargetValidationResult

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

    override fun onDestroy() { instance = null; super.onDestroy() }
}
