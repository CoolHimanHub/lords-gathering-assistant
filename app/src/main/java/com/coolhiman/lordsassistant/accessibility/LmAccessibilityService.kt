package com.coolhiman.lordsassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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
    private var scannerHud: TextView? = null
    private var scannerHudManager: WindowManager? = null

    fun showScannerHud(text: String): Boolean {
        return runCatching {
            val manager = scannerHudManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
            val view = scannerHud ?: TextView(this).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE616181D.toInt())
                setPadding(18, 14, 18, 14)
                elevation = 12f
            }
            if (scannerHud == null) {
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 20
                    y = 90
                }
                manager.addView(view, lp)
                scannerHudManager = manager
                scannerHud = view
            }
            scannerHud?.text = text
            true
        }.getOrElse { false }
    }

    fun hideScannerHud() {
        val view = scannerHud ?: return
        runCatching { scannerHudManager?.removeView(view) }
        scannerHud = null
        scannerHudManager = null
    }

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

    override fun onDestroy() { hideScannerHud(); instance = null; super.onDestroy() }
}
