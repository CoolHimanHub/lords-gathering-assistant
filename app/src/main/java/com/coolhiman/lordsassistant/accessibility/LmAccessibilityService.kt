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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scannerHud: TextView? = null
    private var scannerHudManager: WindowManager? = null
    @Volatile private var scannerHudDesiredText: String? = null

    // The system is allowed to change accessibility-overlay visibility. Keep the
    // HUD owned by the accessibility service and independently heal a detached
    // or hidden window. This is deliberately separate from MediaProjection so
    // capture stalls cannot make the diagnostic surface disappear.
    private val scannerHudKeepAlive = object : Runnable {
        override fun run() {
            val desired = scannerHudDesiredText ?: return
            ensureScannerHud(desired)
            mainHandler.postDelayed(this, 750L)
        }
    }

    private fun ensureScannerHud(text: String): Boolean {
        return runCatching {
            var manager = scannerHudManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
            val existing = scannerHud

            if (existing != null) {
                val attached = existing.parent != null
                val visible = existing.windowVisibility == View.VISIBLE &&
                    existing.visibility == View.VISIBLE &&
                    existing.isShown

                if (!attached || !visible) {
                    runCatching { manager.removeViewImmediate(existing) }
                    scannerHud = null
                    scannerHudManager = null
                    manager = getSystemService(WINDOW_SERVICE) as WindowManager
                } else {
                    existing.text = text
                    return@runCatching true
                }
            }

            val view = TextView(this).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xEE111318.toInt())
                setPadding(22, 16, 22, 16)
                elevation = 24f
                visibility = View.VISIBLE
                minWidth = 260
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 96
            }

            manager.addView(view, lp)
            scannerHudManager = manager
            scannerHud = view
            view.text = text
            true
        }.getOrElse { false }
    }

    fun showScannerHud(text: String): Boolean {
        scannerHudDesiredText = text
        mainHandler.removeCallbacks(scannerHudKeepAlive)
        mainHandler.post(scannerHudKeepAlive)
        return ensureScannerHud(text)
    }

    fun hideScannerHud() {
        scannerHudDesiredText = null
        mainHandler.removeCallbacks(scannerHudKeepAlive)
        val view = scannerHud
        scannerHud = null
        scannerHudManager?.let { manager ->
            if (view != null) runCatching { manager.removeViewImmediate(view) }
        }
        scannerHudManager = null
    }

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
