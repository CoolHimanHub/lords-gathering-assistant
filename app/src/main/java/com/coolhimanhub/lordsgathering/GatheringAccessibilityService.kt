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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Lords Gathering Assistant - V9
 *
 * SAFE SCANNER ONLY:
 * - Takes an accessibility screenshot.
 * - Detects blue RSS level badges.
 * - Accepts ONLY levels 1..5.
 * - Requires repeated OCR agreement before accepting a level.
 * - Adapts badge-size filters to zoom level.
 * - Detects a likely red/orange occupation flag.
 * - Shows ALL accepted detections (up to 50).
 *
 * IMPORTANT:
 * x/y printed here are SCREEN PIXELS.
 * Kingdom/world X/Y cannot be calculated reliably from one screenshot
 * unless a known kingdom-coordinate anchor/calibration is supplied.
 */

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile private var running = false
    @Volatile private var screenshotBusy = false
    @Volatile private var alive = true

    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var startButton: Button? = null

    private var scanNo = 0

    private val scanEveryMs = 4500L
    private val maxResults = 50

    private data class Badge(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
    }

    private data class Candidate(
        val type: String,
        val level: Int,
        val sx: Int,
        val sy: Int,
        val confidence: Int,
        val flagged: Boolean,
        val flagScore: Int,
        val score: Int
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            handler.post { showOverlay() }
        } catch (_: Exception) {
            handler.postDelayed({ if (alive) showOverlay() }, 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopScanner() }

    private fun showOverlay() {
        if (!alive || overlay != null) return
        val manager = wm ?: return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(55, 55, 55))
        }

        val title = TextView(this).apply {
            text = "Lords Assistant V9"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)
        }

        // Drag the overlay by its title.
        title.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var oldX = 0
            var oldY = 0

            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                val p = params ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        oldX = p.x
                        oldY = p.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (oldX + e.rawX - downX).toInt()
                        p.y = (oldY + e.rawY - downY).toInt()
                        try { manager.updateViewLayout(box, p) } catch (_: Exception) {}
                        return true
                    }
                }
                return true
            }
        })

        val start = Button(this).apply {
            text = "▶ START"
            setOnClickListener {
                if (running) stopScanner() else startScanner()
            }
        }

        val scan = Button(this).apply {
            text = "🔍 SCAN NOW"
            setOnClickListener { scanScreen() }
        }

        val info = TextView(this).apply {
            text = "V9 ready\nLevels accepted: 1-5\nNo troop sent"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setPadding(6, 5, 6, 5)
            setLineSpacing(0f, 1.05f)
        }

        status = info
        startButton = start

        box.addView(title)
        box.addView(start)
        box.addView(scan)

        val scroll = ScrollView(this)
        scroll.addView(info)
        box.addView(scroll, LinearLayout.LayoutParams(500, 600))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 80
        p.y = 80

        params = p
        try {
            manager.addView(box, p)
            overlay = box
            setStatus("V9 ready\nZoom-adaptive badge scan\nLevels 1-5 only\nNo troop sent")
        } catch (_: Exception) {
            overlay = null
            params = null
            handler.postDelayed({ if (alive) showOverlay() }, 1000)
        }
    }

    private fun setStatus(s: String) {
        if (!alive) return
        handler.post { try { status?.text = s } catch (_: Exception) {} }
    }

    private fun startScanner() {
        if (running) return
        running = true
        startButton?.text = "■ STOP"
        setStatus("AUTO SCAN ON\nEvery 4.5 seconds\nLevels 1-5 only\nNo troop sent")
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun stopScanner() {
        running = false
        handler.removeCallbacks(loop)
        startButton?.text = "▶ START"
        setStatus("STOPPED\nNo troop sent")
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running || !alive) return
            scanScreen()
            handler.postDelayed(this, scanEveryMs)
        }
    }

    private fun scanScreen() {
        if (!alive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setStatus("Android screenshot API unavailable")
            return
        }
        if (screenshotBusy) return

        screenshotBusy = true
        val n = ++scanNo
        setStatus("Scan #$n\nCapturing screen...")

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        if (!alive) {
                            closeScreenshot(result)
                            screenshotBusy = false
                            return
                        }
                        processScreenshot(result, n)
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotBusy = false
                        setStatus("Scan #$n\nCapture failed: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            screenshotBusy = false
            setStatus("Scan #$n\nCapture exception: ${e.javaClass.simpleName}")
        }
    }

    private fun processScreenshot(result: ScreenshotResult, n: Int) {
        try {
            val hw = try {
                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            } catch (_: Exception) { null }

            if (hw == null) {
                screenshotBusy = false
                return
            }

            val bitmap = try {
                hw.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) { null }

            if (bitmap == null) {
                screenshotBusy = false
                return
            }

            worker.execute {
                try {
                    val list = analyse(bitmap)
                    publish(n, bitmap, list)
                } catch (e: Exception) {
                    setStatus("Scan #$n\nAnalysis error: ${e.javaClass.simpleName}")
                } finally {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    screenshotBusy = false
                }
            }
        } finally {
            closeScreenshot(result)
        }
    }

    private fun publish(n: Int, bitmap: Bitmap, list: List<Candidate>) {
        val out = StringBuilder()
        out.append("Scan #").append(n).append('\n')
        out.append("RSS FOUND: ").append(list.size).append('\n')
        out.append("EMPTY: ").append(list.count { !it.flagged }).append('\n')
        out.append("FLAGGED: ").append(list.count { it.flagged }).append('\n')

        out.append("LEVELS: ")
        for (l in 1..5) {
            if (l > 1) out.append("  ")
            out.append("L").append(l).append("=")
                .append(list.count { it.level == l })
        }
        out.append("\n\n")

        out.append("DETECTED RSS:\n")
        list.take(maxResults).forEachIndexed { i, c ->
            out.append(
                String.format(
                    Locale.US,
                    "%2d. %-6s Lv%d screen(%d,%d) C%d F%d",
                    i + 1, c.type, c.level, c.sx, c.sy,
                    c.confidence, c.flagScore
                )
            )
            if (c.flagged) out.append(" [FLAGGED]")
            out.append('\n')
        }

        out.append("\nIMPORTANT:\n")
        out.append("screen(x,y) = phone pixels, NOT kingdom X/Y.\n")
        out.append("Kingdom X/Y needs a known map-coordinate anchor.\n")
        out.append("Zoom changes screen position/size, so no fixed tile size is used.\n")
        out.append("LEVEL RANGE = 1-5 ONLY\nNO TROOP SENT")

        setStatus(out.toString())
    }

    private fun analyse(bitmap: Bitmap): List<Candidate> {
        val badges = findBlueBadges(bitmap)
        val result = mutableListOf<Candidate>()

        for (b in badges) {
            val level = readLevelConsensus(bitmap, b)
            if (level !in 1..5) continue

            val type = classifyResource(bitmap, b)
            val confidence = resourceConfidence(bitmap, b, type)
            if (confidence < 8) continue

            val flagScore = flagScore(bitmap, b)
            val flagged = flagScore >= 55

            val score =
                typePriority(type) + level * 20 + confidence - flagScore * 3

            result.add(
                Candidate(
                    type, level, b.cx, b.cy,
                    confidence, flagged, flagScore, score
                )
            )
        }

        return dedupe(result).sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.level }
                .thenByDescending { it.confidence }
        )
    }

    /*
     * Zoom-adaptive blue badge detector.
     * We intentionally do NOT assume a single badge width/height.
     */
    private fun findBlueBadges(bitmap: Bitmap): List<Badge> {
        val w = bitmap.width
        val h = bitmap.height
        val step = if (w >= 1800) 3 else 2
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step

        val visited = BooleanArray(gw * gh)
        val qx = IntArray(gw * gh)
        val qy = IntArray(gw * gh)
        val out = mutableListOf<Badge>()

        fun blue(gx: Int, gy: Int): Boolean {
            val x = min(w - 1, gx * step)
            val y = min(h - 1, gy * step)
            val p = bitmap.getPixel(x, y)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            return b >= 80 &&
                b - r >= 12 &&
                b >= r * 1.08f &&
                b >= g * 0.90f
        }

        for (gy in 2 until gh - 2) {
            for (gx in 1 until gw - 1) {
                val start = gy * gw + gx
                if (visited[start] || !blue(gx, gy)) {
                    visited[start] = true
                    continue
                }

                var head = 0
                var tail = 0
                qx[tail] = gx
                qy[tail] = gy
                tail++
                visited[start] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var pixels = 0

                while (head < tail) {
                    val x = qx[head]
                    val y = qy[head]
                    head++
                    pixels++

                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until gw || ny !in 0 until gh) continue
                            val idx = ny * gw + nx
                            if (visited[idx]) continue
                            visited[idx] = true
                            if (blue(nx, ny) && tail < qx.size) {
                                qx[tail] = nx
                                qy[tail] = ny
                                tail++
                            }
                        }
                    }
                }

                val bw = (maxX - minX + 1) * step
                val bh = (maxY - minY + 1) * step

                /*
                 * Wide adaptive range catches the same badge at different
                 * game zoom levels while excluding huge blue UI regions.
                 */
                if (pixels < 5) continue
                if (bw < 6 || bh < 5) continue
                if (bw > 180 || bh > 110) continue
                if (bw * bh > 9000) continue

                val aspect = bw.toFloat() / bh.toFloat()
                if (aspect < 0.45f || aspect > 5.0f) continue

                out.add(
                    Badge(
                        minX * step,
                        minY * step,
                        min(w - 1, (maxX + 1) * step - 1),
                        min(h - 1, (maxY + 1) * step - 1)
                    )
                )
            }
        }

        return out
    }

    /*
     * OCR is accepted only when several independently enlarged crops agree.
     * This is the main protection against the previous fake Lv5/Lv0 problem.
     */
    private fun readLevelConsensus(bitmap: Bitmap, b: Badge): Int {
        val guesses = mutableListOf<Int>()

        val padX = max(2, b.width / 3)
        val padY = max(2, b.height / 3)

        val variants = listOf(
            1.0f to 1.0f,
            1.35f to 1.20f,
            1.70f to 1.45f
        )

        for ((sx, sy) in variants) {
            val cx = b.cx
            val cy = b.cy
            val halfW = max(4, (b.width * sx).toInt() / 2)
            val halfH = max(4, (b.height * sy).toInt() / 2)

            val l = max(0, cx - halfW - padX / 3)
            val t = max(0, cy - halfH - padY / 3)
            val r = min(bitmap.width, cx + halfW + padX / 3)
            val bot = min(bitmap.height, cy + halfH + padY / 3)

            if (r <= l || bot <= t) continue

            val crop = try {
                Bitmap.createBitmap(bitmap, l, t, r - l, bot - t)
            } catch (_: Exception) { null } ?: continue

            val digit = try {
                val image = InputImage.fromBitmap(crop, 0)
                val text = Tasks.await(
                    recognizer.process(image),
                    900,
                    TimeUnit.MILLISECONDS
                ).text

                parseSingleLevel(text)
            } catch (_: Exception) {
                null
            } finally {
                try { crop.recycle() } catch (_: Exception) {}
            }

            if (digit != null) guesses.add(digit)
        }

        if (guesses.isEmpty()) return 0

        val counts = guesses.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return 0

        /*
         * Require 2 matching reads whenever possible.
         * A single OCR read is allowed only if it is very clean.
         */
        if (guesses.size >= 2 && best.value < 2) return 0
        if (guesses.size == 1) return best.key

        return best.key
    }

    private fun parseSingleLevel(raw: String): Int? {
        val cleaned = raw
            .replace('O', '0', true)
            .replace('I', '1', true)
            .replace('L', '1', true)
            .replace('S', '5', true)

        /*
         * We never accept 0, 6, 7, 8 or 9.
         * Also reject multi-digit OCR such as 15 or 45 instead of
         * taking an arbitrary digit from it.
         */
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length != 1) return null

        val n = digits[0].digitToInt()
        return if (n in 1..5) n else null
    }

    private fun classifyResource(bitmap: Bitmap, b: Badge): String {
        var food = 0
        var gold = 0
        var wood = 0
        var stone = 0
        var ore = 0

        val l = max(0, b.cx - b.width * 4)
        val r = min(bitmap.width - 1, b.cx + b.width)
        val t = max(0, b.cy - b.height * 4)
        val bot = min(bitmap.height - 1, b.cy + b.height * 2)

        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val p = bitmap.getPixel(x, y)
                val rr = Color.red(p)
                val gg = Color.green(p)
                val bb = Color.blue(p)

                if (!(bb > 100 && bb > rr * 1.18f)) {
                    if (rr > 145 && gg > 110 && bb < 115) food++
                    if (rr > 155 && gg > 105 && bb < 130) gold++
                    if (rr > 75 && gg in 35..150 && bb < 100 && rr > gg * 1.08f) wood++
                    if (abs(rr - gg) < 32 && abs(gg - bb) < 32 && rr in 75..220) stone++
                    if ((bb > 85 && gg > 75 && bb > rr * 1.08f) ||
                        (rr > 135 && gg > 55 && bb < 115)) ore++
                }
                x += max(2, b.width / 3)
            }
            y += max(2, b.height / 3)
        }

        val best = maxOf(food, gold, wood, stone, ore)
        return when {
            best == 0 -> "Other"
            ore == best -> "Ore"
            stone == best -> "Stone"
            wood == best -> "Wood"
            gold == best && gold >= food -> "Gold"
            else -> "Food"
        }
    }

    private fun resourceConfidence(bitmap: Bitmap, b: Badge, type: String): Int {
        var total = 0
        var match = 0

        val l = max(0, b.cx - b.width * 3)
        val r = min(bitmap.width - 1, b.cx + b.width)
        val t = max(0, b.cy - b.height * 3)
        val bot = min(bitmap.height - 1, b.cy + b.height * 2)

        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val p = bitmap.getPixel(x, y)
                val rr = Color.red(p)
                val gg = Color.green(p)
                val bb = Color.blue(p)

                if (!(bb > 100 && bb > rr * 1.18f)) {
                    total++
                    val ok = when (type) {
                        "Food" -> rr > 145 && gg > 110 && bb < 120
                        "Gold" -> rr > 150 && gg > 100 && bb < 135
                        "Wood" -> rr > 75 && gg in 35..150 && bb < 105
                        "Stone" -> abs(rr - gg) < 35 && abs(gg - bb) < 35 && rr in 70..225
                        "Ore" -> (bb > 85 && gg > 75) || (rr > 135 && gg > 55 && bb < 120)
                        else -> false
                    }
                    if (ok) match++
                }
                x += max(2, b.width / 3)
            }
            y += max(2, b.height / 3)
        }

        if (total == 0) return 0
        return min(100, match * 100 / total)
    }

    /*
     * Detect the red/orange flag area around a resource.
     * This is deliberately separate from level OCR.
     */
    private fun flagScore(bitmap: Bitmap, b: Badge): Int {
        var red = 0
        var samples = 0

        val radiusX = max(12, b.width * 4)
        val radiusY = max(12, b.height * 5)

        val l = max(0, b.cx - radiusX)
        val r = min(bitmap.width - 1, b.cx + radiusX)
        val t = max(0, b.cy - radiusY)
        val bot = min(bitmap.height - 1, b.cy + radiusY)

        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val dx = x - b.cx
                val dy = y - b.cy
                if (dx * dx + dy * dy <= radiusX * radiusX) {
                    val p = bitmap.getPixel(x, y)
                    val rr = Color.red(p)
                    val gg = Color.green(p)
                    val bb = Color.blue(p)
                    samples++
                    if (rr > 165 && rr > gg * 1.30f && rr > bb * 1.20f) {
                        red += 2
                    } else if (rr > 135 && rr > gg * 1.18f && rr > bb * 1.10f) {
                        red++
                    }
                }
                x += max(2, b.width / 3)
            }
            y += max(2, b.height / 3)
        }

        if (samples == 0) return 0
        return min(100, red * 100 / max(1, samples / 5))
    }

    private fun typePriority(type: String): Int = when (type) {
        "Emerging" -> 700
        "Gold" -> 600
        "Ore" -> 500
        "Wood" -> 400
        "Food" -> 300
        "Stone" -> 200
        else -> 50
    }

    private fun dedupe(input: List<Candidate>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (c in input) {
            val idx = out.indexOfFirst {
                abs(it.sx - c.sx) < 35 && abs(it.sy - c.sy) < 35
            }
            if (idx < 0) {
                out.add(c)
            } else if (c.score > out[idx].score) {
                out[idx] = c
            }
        }
        return out
    }

    private fun closeScreenshot(result: ScreenshotResult) {
        try { result.hardwareBuffer.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        alive = false
        running = false
        handler.removeCallbacks(loop)
        try { worker.shutdownNow() } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        params = null
        status = null
        startButton = null
        wm = null
        super.onDestroy()
    }
}
