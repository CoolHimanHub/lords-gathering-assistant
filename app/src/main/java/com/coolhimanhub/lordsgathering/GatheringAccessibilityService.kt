package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    private var scanCount = 0

    // =============================================================
    // ACCESSIBILITY SERVICE
    // =============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events can be used later.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    // =============================================================
    // FLOATING CONTROL
    // =============================================================

    private fun showFloatingControl() {

        if (overlayView != null) {
            return
        }

        val container = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                10,
                8,
                10,
                8
            )

            setBackgroundColor(
                Color.rgb(65, 65, 65)
            )
        }

        // ---------------------------------------------------------
        // TITLE / DRAG HANDLE
        // ---------------------------------------------------------

        val title = TextView(this).apply {

            text = "Lords Assistant"

            textSize = 16f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                8,
                4,
                8,
                8
            )

            setOnTouchListener(
                object : View.OnTouchListener {

                    private var startX = 0f
                    private var startY = 0f

                    private var startParamX = 0
                    private var startParamY = 0

                    override fun onTouch(
                        view: View?,
                        event: MotionEvent
                    ): Boolean {

                        val params =
                            overlayParams
                                ?: return false

                        when (event.actionMasked) {

                            MotionEvent.ACTION_DOWN -> {

                                startX = event.rawX
                                startY = event.rawY

                                startParamX = params.x
                                startParamY = params.y

                                return true
                            }

                            MotionEvent.ACTION_MOVE -> {

                                val dx =
                                    event.rawX - startX

                                val dy =
                                    event.rawY - startY

                                params.x =
                                    (
                                        startParamX + dx
                                    ).toInt()

                                params.y =
                                    (
                                        startParamY + dy
                                    ).toInt()

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

        // ---------------------------------------------------------
        // START / STOP
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // SCAN
        // ---------------------------------------------------------

        val scanButton = Button(this).apply {

            text = "🔍 SCAN"

            setOnClickListener {

                scanScreen()
            }
        }

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // OVERLAY WINDOW
        // ---------------------------------------------------------

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

        // Initial position

        params.x = 100
        params.y = 150

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

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

    // =============================================================
    // AUTOMATION
    // =============================================================

    private fun startAutomation() {

        if (running) {
            return
        }

        running = true

        updateInfo(
            "AUTO TEST - scan only"
        )

        handler.post(
            scanRunnable
        )
    }

    private fun stopAutomation() {

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        updateInfo(
            "Stopped - no troops sent"
        )
    }

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (!running) {
                    return
                }

                scanScreen()

                // Scan every 3 seconds

                handler.postDelayed(
                    this,
                    3000
                )
            }
        }

    // =============================================================
    // SCREEN SCANNER
    // =============================================================

    private fun scanScreen() {

        scanCount++

        if (
            Build.VERSION.SDK_INT <
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

                    try {

                        // -------------------------------------------------
                        // STEP 1
                        // Get the hardware bitmap.
                        // -------------------------------------------------

                        val hardwareBitmap =
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                        if (hardwareBitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - bitmap conversion failed"
                            )

                            screenshot.hardwareBuffer.close()

                            return
                        }

                        // -------------------------------------------------
                        // STEP 2
                        // IMPORTANT:
                        // Convert HARDWARE bitmap into a normal bitmap.
                        //
                        // getPixel() cannot safely analyse a HARDWARE
                        // bitmap.
                        // -------------------------------------------------

                        val bitmap =
                            hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        if (bitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - bitmap copy failed"
                            )

                            screenshot.hardwareBuffer.close()

                            return
                        }

                        // -------------------------------------------------
                        // STEP 3
                        // Screenshot successfully captured.
                        // -------------------------------------------------

                        updateInfo(
                            "Scan #$scanCount - screen captured " +
                                "${bitmap.width}x${bitmap.height}"
                        )

                        // -------------------------------------------------
                        // STEP 4
                        // Analyse the software bitmap.
                        // -------------------------------------------------

                        analyseScreen(
                            bitmap
                        )

                        // -------------------------------------------------
                        // STEP 5
                        // Clean up.
                        // -------------------------------------------------

                        screenshot.hardwareBuffer.close()

                        bitmap.recycle()

                    } catch (e: Exception) {

                        try {

                            screenshot.hardwareBuffer.close()

                        } catch (_: Exception) {
                        }

                        updateInfo(
                            "Scan #$scanCount - error: " +
                                e.javaClass.simpleName
                        )
                    }
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    updateInfo(
                        "Scan #$scanCount - capture failed: " +
                            errorCode
                    )
                }
            }
        )
    }

    // =============================================================
    // IMAGE ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val width =
            bitmap.width

        val height =
            bitmap.height

        var bluePixels = 0

        var orangePixels = 0

        var greenPixels = 0

        // We don't need to inspect every single pixel.
        // Sampling every 4 pixels is enough for this test.

        val step = 4

        var y = 80

        while (
            y < height - 30
        ) {

            var x = 10

            while (
                x < width - 10
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)

                // -------------------------------------------------
                // BLUE
                // -------------------------------------------------

                if (
                    b > 110 &&
                    b > r * 1.25 &&
                    b > g * 1.05
                ) {

                    bluePixels++
                }

                // -------------------------------------------------
                // ORANGE
                // -------------------------------------------------

                if (
                    r > 150 &&
                    g > 70 &&
                    g < 190 &&
                    b < 100 &&
                    r > g * 1.15
                ) {

                    orangePixels++
                }

                // -------------------------------------------------
                // GREEN
                // -------------------------------------------------

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

        // ---------------------------------------------------------
        // TEST RESULT
        // ---------------------------------------------------------

        updateInfo(

            "Scan #$scanCount - ${width}x$height\n" +

                "Visual signals: " +

                "blue=$bluePixels " +

                "orange=$orangePixels " +

                "green=$greenPixels\n" +

                "SAFE TEST: no troop sent"
        )
    }

    // =============================================================
    // UPDATE OVERLAY
    // =============================================================

    private fun updateInfo(
        message: String
    ) {

        val container =
            overlayView
                ?: return

        if (
            container.childCount < 4
        ) {

            return
        }

        val info =
            container.getChildAt(3)
                as? TextView
                ?: return

        info.text =
            message
    }

    // =============================================================
    // ACCESSIBILITY TAP
    // =============================================================

    private fun tap(
        x: Float,
        y: Float
    ) {

        val path =
            Path()

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

    // =============================================================
    // SERVICE DESTROY
    // =============================================================

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
