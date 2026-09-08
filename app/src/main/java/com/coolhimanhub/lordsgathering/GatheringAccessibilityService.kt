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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var overlayView: View? = null
    private var scanCount = 0

    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Game map is graphical, so screenshot analysis is used.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    // =============================================================
    // FLOATING OVERLAY
    // =============================================================

    private fun showFloatingControl() {

        if (overlayView != null) return

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
                            overlayParams ?: return false

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
                                    (startParamX + dx).toInt()

                                params.y =
                                    (startParamY + dy).toInt()

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

        val params = WindowManager.LayoutParams(

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

    // =============================================================
    // AUTOMATION
    // =============================================================

    private fun startAutomation() {

        if (running) return

        running = true

        updateInfo(
            "Automation started"
        )

        handler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        updateInfo(
            "Automation stopped"
        )
    }

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (!running) return

                scanScreen()

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
                "Scan #$scanCount - screen capture unavailable"
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

                    val hardwareBitmap =
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

                    if (hardwareBitmap == null) {

                        updateInfo(
                            "Scan #$scanCount - capture failed"
                        )

                        return
                    }

                    val bitmap =
                        try {

                            hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        } catch (_: Exception) {

                            null
                        }

                    try {

                        hardwareBitmap.recycle()

                    } catch (_: Exception) {
                    }

                    if (bitmap == null) {

                        updateInfo(
                            "Scan #$scanCount - bitmap conversion failed"
                        )

                        return
                    }

                    val width =
                        bitmap.width

                    val height =
                        bitmap.height

                    // -------------------------------------------------
                    // ORANGE MARCH DETECTION
                    // -------------------------------------------------

                    val result =
                        detectOrangeMarches(
                            bitmap
                        )

                    if (result.detected) {

                        updateInfo(
                            "⚠ ORANGE MARCH: " +
                                    "${result.paths} path(s)"
                        )

                    } else {

                        updateInfo(
                            "Scan #$scanCount - " +
                                    "no orange attack path"
                        )
                    }

                    try {

                        bitmap.recycle()

                    } catch (_: Exception) {
                    }
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    updateInfo(
                        "Scan #$scanCount - capture failed ($errorCode)"
                    )
                }
            }
        )
    }

    // =============================================================
    // ORANGE MARCH DETECTION
    // =============================================================

    private data class OrangeDetection(
        val detected: Boolean,
        val paths: Int
    )

    /**
     * Detects the bright orange/yellow-orange march arrows
     * visible on the Lords Mobile world map.
     *
     * This stage only detects the orange path.
     *
     * It does NOT return troops yet.
     */
    private fun detectOrangeMarches(
        bitmap: Bitmap
    ): OrangeDetection {

        val width =
            bitmap.width

        val height =
            bitmap.height

        /*
         * We don't need to examine every pixel.
         * A small sampling step makes scanning much faster.
         */
        val step =
            max(
                2,
                min(width, height) / 700
            )

        var orangePixels = 0

        /*
         * Divide the screen into regions.
         * This helps distinguish a real march path
         * from isolated orange UI pixels.
         */
        val regions =
            HashMap<Int, Int>()

        var y = 0

        while (y < height) {

            var x = 0

            while (x < width) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                if (isMarchOrange(pixel)) {

                    orangePixels++

                    /*
                     * Region size is intentionally broad.
                     */
                    val rx =
                        x / 120

                    val ry =
                        y / 120

                    val key =
                        ry * 10000 + rx

                    regions[key] =
                        (regions[key] ?: 0) + 1
                }

                x += step
            }

            y += step
        }

        if (orangePixels < 20) {

            return OrangeDetection(
                detected = false,
                paths = 0
            )
        }

        /*
         * A genuine march normally produces orange
         * pixels across multiple neighbouring regions.
         */
        val strongRegions =
            regions.values.count {
                it >= 4
            }

        if (strongRegions == 0) {

            return OrangeDetection(
                detected = false,
                paths = 0
            )
        }

        /*
         * At this stage we conservatively report one or more
         * candidate paths. The next stage will connect the
         * regions and determine the actual arrow direction.
         */
        val estimatedPaths =
            min(
                5,
                max(
                    1,
                    strongRegions / 2
                )
            )

        return OrangeDetection(
            detected = true,
            paths = estimatedPaths
        )
    }

    // =============================================================
    // ORANGE COLOR FILTER
    // =============================================================

    private fun isMarchOrange(
        color: Int
    ): Boolean {

        val r =
            Color.red(color)

        val g =
            Color.green(color)

        val b =
            Color.blue(color)

        /*
         * Bright orange used by march indicators.
         *
         * Terrain is generally much darker/browner,
         * so these thresholds intentionally favour
         * bright orange pixels.
         */
        return (
                r >= 190 &&
                g >= 70 &&
                g <= 210 &&
                b <= 110 &&
                r > g + 55
                )
    }

    // =============================================================
    // UPDATE STATUS
    // =============================================================

    private fun updateInfo(
        message: String
    ) {

        val container =
            overlayView as? LinearLayout
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

    fun tap(
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
    // DISTANCE HELPER
    // =============================================================

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {

        val dx =
            x1 - x2

        val dy =
            y1 - y2

        return sqrt(
            dx * dx + dy * dy
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
