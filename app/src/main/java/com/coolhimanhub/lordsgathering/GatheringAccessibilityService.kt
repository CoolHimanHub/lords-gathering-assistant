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

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Scanner will be connected here in the next step.
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

        startStopButton = Button(this).apply {
            text = "▶ START"

            setOnClickListener {
                if (running) {
                    stopAutomation()
                } else {
                    startAutomation()
                }
            }
        }

        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        container.addView(title)
        container.addView(startStopButton)
        container.addView(statusText)

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

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        windowManager.addView(container, params)

        overlayView = container
    }

    private fun startAutomation() {

        if (running) return

        running = true

        startStopButton.text = "■ STOP"
        statusText.text = "Scanning..."

        /*
         * IMPORTANT:
         * We deliberately do NOT tap fixed coordinates anymore.
         *
         * The next scanner module will:
         *
         * 1. Capture the game screen.
         * 2. Find visible RSS text.
         * 3. Detect RSS type.
         * 4. Detect RSS level.
         * 5. Determine tile position.
         * 6. Compare against the user's RSS preference.
         * 7. Select the best candidate.
         */

        startScanCycle()
    }

    private fun stopAutomation() {

        running = false

        if (::startStopButton.isInitialized) {
            startStopButton.text = "▶ START"
        }

        if (::statusText.isInitialized) {
            statusText.text = "Stopped"
        }

        handler.removeCallbacksAndMessages(null)
    }

    private fun startScanCycle() {

        if (!running) return

        statusText.text = "Scanner ready"

        /*
         * Scanner implementation goes here.
         *
         * No automatic tap is performed yet.
         * This prevents the app from blindly tapping
         * arbitrary locations on the game screen.
         */

        handler.postDelayed({
            if (running) {
                statusText.text = "Waiting for scanner..."
                startScanCycle()
            }
        }, 3000)
    }

    private fun tap(x: Float, y: Float) {

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
