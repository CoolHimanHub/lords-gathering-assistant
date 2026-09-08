package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class GatheringAccessibilityService : AccessibilityService() {

    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    private var scanCount = 0
    private var running = false

    private val scanHandler =
        android.os.Handler(android.os.Looper.getMainLooper())

    // ------------------------------------------------------------
    // SERVICE
    // ------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for future game/UI detection.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    // ------------------------------------------------------------
    // FLOATING CONTROL
    // ------------------------------------------------------------

    private fun showFloatingControl() {

        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(65, 65, 65))
        }

        // --------------------------------------------------------
        // DRAG HANDLE
        // --------------------------------------------------------

        val title = TextView(this).apply {
            text = "Lords Assistant"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)

            setOnTouchListener(
                object : View.OnTouchListener {

                    private var downX = 0f
                    private var downY = 0f
                    private var startX = 0
                    private var startY = 0

                    override fun onTouch(
                        v: View?,
                        event: MotionEvent
                    ): Boolean {

                        val params =
                            overlayParams ?: return false

                        when (event.actionMasked) {

                            MotionEvent.ACTION_DOWN -> {

                                downX = event.rawX
                                downY = event.rawY

                                startX = params.x
                                startY = params.y

                                return true
                            }

                            MotionEvent.ACTION_MOVE -> {

                                params.x =
                                    startX +
                                            (event.rawX - downX).toInt()

                                params.y =
                                    startY +
                                            (event.rawY - downY).toInt()

                                try {
                                    windowManager?.updateViewLayout(
                                        container,
                                        params
                                    )
                                } catch (_: Exception) {
                                }

                                return true
                            }

                            MotionEvent.ACTION_UP -> {
                                return true
                            }
                        }

                        return true
                    }
                }
            )
        }

        // --------------------------------------------------------
        // START / STOP
        // --------------------------------------------------------

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

        // --------------------------------------------------------
        // SCAN
        // --------------------------------------------------------

        val scanButton = Button(this).apply {

            text = "🔍 SCAN"

            setOnClickListener {
                scanScreen()
            }
        }

        // --------------------------------------------------------
        // STATUS
        // --------------------------------------------------------

        val info = TextView(this).apply {

            text = "Scanner ready"

            textSize = 11f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                4,
                5,
                4,
                2
            )
        }

        container.addView(title)
        container.addView(startStop)
        container.addView(scanButton)
        container.addView(info)

        // --------------------------------------------------------
        // OVERLAY WINDOW
        // --------------------------------------------------------

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 100
        params.y = 150

        windowManager =
            getSystemService(WINDOW_SERVICE)
                    as WindowManager

        overlayParams = params

        try {

            windowManager?.addView(
                container,
                params
            )

            overlayView = container

        } catch (_: Exception) {

            overlayView = null
        }
    }

    // ------------------------------------------------------------
    // AUTOMATION
    // ------------------------------------------------------------

    private fun startAutomation() {

        if (running) return

        running = true

        updateInfo(
            "AUTO TEST - scan only"
        )

        scanHandler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false

        scanHandler.removeCallbacks(
            scanRunnable
        )

        updateInfo(
            "Stopped - no troops sent"
        )
    }

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (!running) return

                scanScreen()

                scanHandler.postDelayed(
                    this,
                    3000
                )
            }
        }

    // ------------------------------------------------------------
    // SCREEN CAPTURE
    // ------------------------------------------------------------

    private fun scanScreen() {

        scanCount++

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {

            updateInfo(
                "Scan #$scanCount - Android version unsupported"
            )

            return
        }

        updateInfo(
            "Scan #$scanCount - capturing..."
        )

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: ScreenshotResult
                ) {

                    val bitmap =
                        try {

                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                        } catch (_: Exception) {
                            null
                        }

                    try {
                        screenshot.hardwareBuffer.close()
                    } catch (_: Exception) {
                    }

                    if (bitmap == null) {

                        updateInfo(
                            "Scan #$scanCount - capture failed"
                        )

                        return
                    }

                    analyseScreen(bitmap)

                    try {
                        bitmap.recycle()
                    } catch (_: Exception) {
                    }
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    updateInfo(
                        "Scan #$scanCount - capture failed: $errorCode"
                    )
                }
            }
        )
    }

    // ------------------------------------------------------------
    // FIRST VISUAL ANALYSIS
    //
    // IMPORTANT:
    // This stage does NOT tap anything.
    //
    // We are deliberately conservative.
    // ------------------------------------------------------------

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val width = bitmap.width
        val height = bitmap.height

        var blueBadgePixels = 0
        var orangePixels = 0
        var greenPixels = 0

        /*
         * Lords Mobile uses blue level markers around
         * resource tiles.
         *
         * This is only a candidate signal.
         *
         * It is NOT yet considered proof that something
         * is an RSS tile.
         */

        val step = 4

        var y = 80

        while (y < height - 30) {

            var x = 10

            while (x < width - 10) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Blue/cyan candidate pixels
                if (
                    b > 110 &&
                    b > r * 1.25 &&
                    b > g * 1.05
                ) {
                    blueBadgePixels++
                }

                // Orange/yellow candidate pixels
                if (
                    r > 150 &&
                    g > 70 &&
                    g < 190 &&
                    b < 100 &&
                    r > g * 1.15
                ) {
                    orangePixels++
                }

                // Green terrain/resource candidate
                if (
                    g > 70 &&
                    g > r * 1.20 &&
                    g > b * 1.10
                ) {
                    greenPixels++
                }

                x += step
            }

            y += step
        }

        /*
         * We intentionally do NOT call tap().
         *
         * We also do NOT classify a tile as EMPTY merely
         * because we didn't see an orange line.
         */

        val message =
            "Scan #$scanCount - " +
                    "${width}x${height}\n" +
                    "Visual signals: " +
                    "blue=$blueBadgePixels " +
                    "orange=$orangePixels " +
                    "green=$greenPixels\n" +
                    "SAFE TEST: no troop sent"

        updateInfo(message)
    }

    // ------------------------------------------------------------
    // STATUS
    // ------------------------------------------------------------

    private fun updateInfo(
        message: String
    ) {

        val container =
            overlayView ?: return

        if (container.childCount < 4) {
            return
        }

        val info =
            container.getChildAt(3)
                    as? TextView
                ?: return

        info.text = message
    }

    // ------------------------------------------------------------
    // ACCESSIBILITY TAP
    //
    // Kept for the later gathering stage.
    // NOT USED by the current scanner.
    // ------------------------------------------------------------

    private fun tap(
        x: Float,
        y: Float
    ) {

        val path = Path()

        path.moveTo(
            x,
            y
        )

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        100
                    )
                )
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    // ------------------------------------------------------------
    // DESTROY
    // ------------------------------------------------------------

    override fun onDestroy() {

        stopAutomation()

        overlayView?.let {

            try {
                windowManager?.removeView(
                    it
                )
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
        windowManager = null

        super.onDestroy()
    }
}
