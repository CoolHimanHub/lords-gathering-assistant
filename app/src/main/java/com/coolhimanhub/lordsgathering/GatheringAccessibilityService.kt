package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var overlayView: View? = null

    private var x = 500f
    private var y = 800f

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (running) {
                tap(x, y)
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events can be handled here later.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    private fun showFloatingControl() {

        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.DKGRAY)
        }

        val title = TextView(this).apply {
            text = "Lords Assistant"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val startStop = Button(this).apply {
            text = "▶ START"

            setOnClickListener {
                if (running) {
                    stopAutomation()
                    text = "▶ START"
                } else {
                    startAutomation()
                    text = "■ STOP"
                }
            }
        }

        val info = TextView(this).apply {
            text = "Tap test: ($x, $y)"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        container.addView(title)
        container.addView(startStop)
        container.addView(info)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 150

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        windowManager.addView(container, params)

        overlayView = container
    }

    private fun startAutomation() {

        if (running) return

        running = true

        handler.removeCallbacks(tapRunnable)
        handler.post(tapRunnable)
    }

    private fun stopAutomation() {

        running = false
        handler.removeCallbacks(tapRunnable)
    }

    fun tap(x: Float, y: Float) {

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    100
                )
            )
            .build()

        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {

        stopAutomation()

        overlayView?.let {
            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlayView = null

        super.onDestroy()
    }
}
