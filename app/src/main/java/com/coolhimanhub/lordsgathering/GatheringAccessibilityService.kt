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

    private var scanCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Game-screen events will be processed by the scanner later.
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

        val scanButton = Button(this).apply {
            text = "🔍 SCAN"

            setOnClickListener {
                scanScreen()
            }
        }

        val info = TextView(this).apply {
            text = "Scanner ready"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        container.addView(title)
        container.addView(startStop)
        container.addView(scanButton)
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

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        windowManager.addView(container, params)

        overlayView = container
    }

    private fun startAutomation() {

        if (running) return

        running = true

        handler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false
        handler.removeCallbacks(scanRunnable)
    }

    private val scanRunnable = object : Runnable {

        override fun run() {

            if (!running) return

            scanScreen()

            handler.postDelayed(this, 3000)
        }
    }

    private fun scanScreen() {

        scanCount++

        /*
         * SCREEN SCANNER FOUNDATION
         *
         * The next stage will:
         *
         * 1. Capture the Lords Mobile screen.
         * 2. Find nearby RSS tiles.
         * 3. Detect RSS type:
         *      Gems
         *      Emerging
         *      Gold
         *      Ore
         *      Stone
         *      Wood
         *      Food
         *      Other
         * 4. Detect RSS level.
         * 5. Calculate distance from the player's location.
         * 6. Apply the user's saved RSS preference.
         * 7. Select the best tile.
         * 8. Tap the selected tile.
         */

        updateInfo("Scan #$scanCount - screenshot scanner ready")
    }

    private fun updateInfo(message: String) {

        val container = overlayView as? LinearLayout
            ?: return

        if (container.childCount < 4) return

        val info = container.getChildAt(3) as? TextView
            ?: return

        info.text = message
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

           
