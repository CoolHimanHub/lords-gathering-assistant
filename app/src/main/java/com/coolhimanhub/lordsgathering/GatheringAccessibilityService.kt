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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

            updateInfo(
                "Scan #$scanCount - Android too old"
            )

            return
        }

        updateInfo(
            "Scan #$scanCount - capturing..."
        )

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: ScreenshotResult
                ) {

                    try {

                        val hardwareBitmap =
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                        screenshot.hardwareBuffer.close()

                        if (hardwareBitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - bitmap failed"
                            )

                            return
                        }

                        val bitmap =
                            hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        hardwareBitmap.recycle()

                        if (bitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - conversion failed"
                            )

                            return
                        }

                        val regions =
                            detectRssRegions(bitmap)

                        updateInfo(
                            "Scan #$scanCount - $regions regions found"
                        )

                        bitmap.recycle()

                    } catch (e: Exception) {

                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                        }

                        updateInfo(
                            "Scan #$scanCount - analysis error"
                        )
                    }
                }

                override fun onFailure(errorCode: Int) {

                    updateInfo(
                        "Scan #$scanCount - capture failed ($errorCode)"
                    )
                }
            }
        )
    }

    /*
     * STAGE 2 RSS DETECTOR
     *
     * Instead of counting every colourful 8x8 block,
     * this groups neighbouring colourful blocks into
     * larger connected regions.
     *
     * IMPORTANT:
     * This stage still does NOT tap anything.
     */

    private fun detectRssRegions(bitmap: Bitmap): Int {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        if (originalWidth <= 0 || originalHeight <= 0) {
            return 0
        }

        /*
         * Scale the screen down for faster analysis.
         */

        val targetWidth = 320

        val scale =
            targetWidth.toFloat() /
                    originalWidth.toFloat()

        val targetHeight =
            (originalHeight * scale).toInt()

        if (targetHeight <= 0) {
            return 0
        }

        val smallBitmap =
            Bitmap.createScaledBitmap(
                bitmap,
                targetWidth,
                targetHeight,
                true
            )

        val pixels =
            IntArray(targetWidth * targetHeight)

        smallBitmap.getPixels(
            pixels,
            0,
            targetWidth,
            0,
            0,
            targetWidth,
            targetHeight
        )

        smallBitmap.recycle()

        /*
         * We work on a coarse grid.
         */

        val blockSize = 8

        val gridWidth =
            (targetWidth + blockSize - 1) / blockSize

        val gridHeight =
            (targetHeight + blockSize - 1) / blockSize

        val active =
            Array(gridHeight) {
                BooleanArray(gridWidth)
            }

        /*
         * Detect colourful blocks.
         */

        for (gy in 0 until gridHeight) {

            for (gx in 0 until gridWidth) {

                val startX =
                    gx * blockSize

                val startY =
                    gy * blockSize

                val endX =
                    minOf(
                        startX + blockSize,
                        targetWidth
                    )

                val endY =
                    minOf(
                        startY + blockSize,
                        targetHeight
                    )

                var colourful = 0
                var total = 0

                for (y in startY until endY) {

                    for (x in startX until endX) {

                        val pixel =
                            pixels[
                                y * targetWidth + x
                            ]

                        val r =
                            Color.red(pixel)

                        val g =
                            Color.green(pixel)

                        val b =
                            Color.blue(pixel)

                        val maxValue =
                            maxOf(r, g, b)

                        val minValue =
                            minOf(r, g, b)

                        val saturation =
                            maxValue - minValue

                        if (
                            maxValue > 90 &&
                            saturation > 45
                        ) {
                            colourful++
                        }

                        total++
                    }
                }

                if (
                    total > 0 &&
                    colourful.toFloat() /
                    total.toFloat() > 0.35f
                ) {
                    active[gy][gx] = true
                }
            }
        }

        /*
         * Connected-component search.
         */

        val visited =
            Array(gridHeight) {
                BooleanArray(gridWidth)
            }

        var regions = 0

        for (gy in 0 until gridHeight) {

            for (gx in 0 until gridWidth) {

                if (!active[gy][gx]) {
                    continue
                }

                if (visited[gy][gx]) {
                    continue
                }

                /*
                 * Flood fill.
                 */

                val queue =
                    ArrayDeque<Pair<Int, Int>>()

                queue.add(
                    Pair(gx, gy)
                )

                visited[gy][gx] = true

                var size = 0

                while (queue.isNotEmpty()) {

                    val current =
                        queue.removeFirst()

                    val cx = current.first
                    val cy = current.second

                    size++

                    /*
                     * Four neighbouring directions.
                     */

                    val directions =
                        arrayOf(
                            Pair(1, 0),
                            Pair(-1, 0),
                            Pair(0, 1),
                            Pair(0, -1)
                        )

                    for (direction in directions) {

                        val nx =
                            cx + direction.first

                        val ny =
                            cy + direction.second

                        if (
                            nx < 0 ||
                            ny < 0 ||
                            nx >= gridWidth ||
                            ny >= gridHeight
                        ) {
                            continue
                        }

                        if (!active[ny][nx]) {
                            continue
                        }

                        if (visited[ny][nx]) {
                            continue
                        }

                        visited[ny][nx] = true

                        queue.add(
                            Pair(nx, ny)
                        )
                    }
                }

                /*
                 * Ignore tiny coloured noise.
                 */

                if (size >= 3) {
                    regions++
                }
            }
        }

        return regions
    }

    private fun updateInfo(message: String) {

        val container =
            overlayView as? LinearLayout
                ?: return

        if (container.childCount < 4) {
            return
        }

        val info =
            container.getChildAt(3) as? TextView
                ?: return

        info.text = message
    }

    /*
     * Tap helper.
     *
     * NOT USED BY THE SCANNER YET.
     */

    fun tap(x: Float, y: Float) {

        val path = Path()

        path.moveTo(x, y)

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
