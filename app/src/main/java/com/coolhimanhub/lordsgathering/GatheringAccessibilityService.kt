package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
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
 * Lords Assistant FINAL scanner
 *
 * SAFE SCANNER ONLY - no troop sending and no map movement.
 *
 * Important fixes over the previous V9:
 * 1. A level is accepted ONLY when OCR sees an actual digit 1..5.
 *    Letters such as S/Z/L/I are NEVER converted into levels.
 * 2. Level OCR is performed inside the blue badge itself, with three
 *    independently enlarged crops. At least two readings must agree.
 * 3. Badge candidates are rejected when their geometry is implausible.
 * 4. Screenshot coordinates are always clamped to the actual bitmap size.
 * 5. Current map-center X/Y is read from the game's top coordinate display.
 *    Per-resource kingdom X/Y is NOT guessed without a calibrated map scale.
 * 6. Zoom does not use a fixed tile size; badge dimensions are measured
 *    from the current screenshot.
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
    private val maxResults = 40

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
        val flagScore: Int,
        val flagged: Boolean,
        val score: Int
    )

    private data class MapAnchor(val x: Int, val y: Int)

    override fun onServiceConnected() {
        super.onServiceConnected()
        alive = true
        wm = try { getSystemService(WINDOW_SERVICE) as WindowManager } catch (_: Exception) { null }
        handler.post { showOverlay() }
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
            text = "Lords Assistant FINAL"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)
        }

        title.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var oldX = 0
            var oldY = 0
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                val p = params ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        oldX = p.x; oldY = p.y
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
            setOnClickListener { if (running) stopScanner() else startScanner() }
        }

        val scan = Button(this).apply {
            text = "🔍 SCAN NOW"
            setOnClickListener { scanScreen() }
        }

        val info = TextView(this).apply {
            text = "FINAL scanner ready\nLevels 1-5 only\nStrict badge OCR\nNo troop sent"
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
            setStatus("FINAL ready\nStrict Lv1-Lv5 badge detection\nZoom-adaptive\nNo troop sent")
        } catch (_: Exception) {
            overlay = null
            params = null
            handler.postDelayed({ if (alive) showOverlay() }, 1000)
        }
    }

    private fun setStatus(text: String) {
        if (!alive) return
        handler.post { try { status?.text = text } catch (_: Exception) {} }
    }

    private fun startScanner() {
        if (running) return
        running = true
        startButton?.text = "■ STOP"
        setStatus("AUTO SCAN ON\nEvery 4.5 seconds\nStrict Lv1-Lv5\nNo troop sent")
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
                        setStatus("Scan #$n\nCapture failed: $errorCode\nNo troop sent")
                    }
                }
            )
        } catch (e: Exception) {
            screenshotBusy = false
            setStatus("Scan #$n\nCapture exception: ${e.javaClass.simpleName}\nNo troop sent")
        }
    }

    private fun processScreenshot(result: ScreenshotResult, n: Int) {
        try {
            val hw = try {
                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            } catch (_: Exception) { null }
            if (hw == null) {
                screenshotBusy = false
                setStatus("Scan #$n\nBitmap conversion failed")
                return
            }
            val bitmap = try { hw.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
            if (bitmap == null) {
                screenshotBusy = false
                setStatus("Scan #$n\nBitmap copy failed")
                return
            }

            worker.execute {
                try {
                    analyse(bitmap, n)
                } catch (e: Exception) {
                    setStatus("Scan #$n\nAnalysis error: ${e.javaClass.simpleName}\nNo troop sent")
                } finally {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    screenshotBusy = false
                }
            }
        } finally {
            closeScreenshot(result)
        }
    }

    private fun analyse(bitmap: Bitmap, n: Int) {
        val badges = findBlueBadges(bitmap)
        if (badges.isEmpty()) {
            setStatus("Scan #$n\nBlue RSS badges not found\nNo troop sent")
            return
        }

        val anchor = readMapAnchor(bitmap)
        val candidates = mutableListOf<Candidate>()

        for (badge in badges) {
            val level = readStrictLevel(bitmap, badge)
            if (level !in 1..5) continue

            val type = classifyResource(bitmap, badge)
            val confidence = resourceConfidence(bitmap, badge, type)
            if (type == "Other" || confidence < 24) continue

            val flag = flagScore(bitmap, badge)
            val flagged = flag >= 55
            val score = typePriority(type) + level * 25 + confidence - flag * 2

            candidates += Candidate(
                type = type,
                level = level,
                sx = badge.cx.coerceIn(0, bitmap.width - 1),
                sy = badge.cy.coerceIn(0, bitmap.height - 1),
                confidence = confidence,
                flagScore = flag,
                flagged = flagged,
                score = score
            )
        }

        val list = dedupe(candidates).sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.level }
                .thenByDescending { it.confidence }
        )

        publish(n, bitmap, list, anchor, badges.size)
    }

    /*
     * Detect blue badge components. UI regions are filtered by geometry and
     * by requiring a compact badge-like connected component.
     */
    private fun findBlueBadges(bitmap: Bitmap): List<Badge> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()

        val step = when {
            w >= 2600 -> 3
            w >= 1600 -> 2
            else -> 1
        }
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val visited = BooleanArray(gw * gh)
        val qx = IntArray(gw * gh)
        val qy = IntArray(gw * gh)
        val out = mutableListOf<Badge>()

        fun isBlue(gx: Int, gy: Int): Boolean {
            val x = min(w - 1, gx * step)
            val y = min(h - 1, gy * step)
            val p = bitmap.getPixel(x, y)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            return b >= 95 && b - r >= 18 && b >= g * 0.96f && b >= r * 1.12f
        }

        for (gy in 1 until gh - 1) {
            for (gx in 1 until gw - 1) {
                val idx0 = gy * gw + gx
                if (visited[idx0]) continue
                visited[idx0] = true
                if (!isBlue(gx, gy)) continue

                var head = 0
                var tail = 0
                qx[tail] = gx
                qy[tail] = gy
                tail++
                var minX = gx; var maxX = gx
                var minY = gy; var maxY = gy
                var count = 0

                while (head < tail) {
                    val x = qx[head]
                    val y = qy[head]
                    head++
                    count++
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)

                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until gw || ny !in 0 until gh) continue
                        val idx = ny * gw + nx
                        if (visited[idx]) continue
                        visited[idx] = true
                        if (isBlue(nx, ny) && tail < qx.size) {
                            qx[tail] = nx; qy[tail] = ny; tail++
                        }
                    }
                }

                val l = minX * step
                val t = minY * step
                val r = min(w - 1, (maxX + 1) * step - 1)
                val b = min(h - 1, (maxY + 1) * step - 1)
                val bw = r - l + 1
                val bh = b - t + 1
                if (count < 6) continue
                if (bw < 7 || bh < 6) continue
                if (bw > 150 || bh > 95) continue
                if (bw * bh > 7000) continue
                val aspect = bw.toFloat() / bh.toFloat()
                if (aspect !in 0.55f..4.2f) continue

                // Reject obvious UI-scale blue components.
                if (l < (w * 0.20f).toInt() && t < (h * 0.20f).toInt() && bw > 70) continue
                if (r > (w * 0.82f).toInt() && t > (h * 0.65f).toInt()) continue

                out += Badge(l, t, r, b)
            }
        }
        return out
    }

    /*
     * Strict level OCR. This intentionally does NOT perform S->5, Z->2,
     * L->1, I->1 or O->0 substitutions. False positives are worse than
     * missed nodes for this scanner.
     */
    private fun readStrictLevel(bitmap: Bitmap, b: Badge): Int {
        val guesses = mutableListOf<Int>()
        val scales = listOf(1.0f to 1.0f, 1.45f to 1.20f, 1.85f to 1.45f)

        for ((sx, sy) in scales) {
            val cx = b.cx
            val cy = b.cy
            val halfW = max(4, (b.width * sx / 2f).toInt())
            val halfH = max(4, (b.height * sy / 2f).toInt())
            val l = max(0, cx - halfW)
            val t = max(0, cy - halfH)
            val r = min(bitmap.width, cx + halfW + 1)
            val bot = min(bitmap.height, cy + halfH + 1)
            if (r <= l || bot <= t) continue

            val crop = try { Bitmap.createBitmap(bitmap, l, t, r - l, bot - t) } catch (_: Exception) { null } ?: continue
            val enlarged = try {
                Bitmap.createScaledBitmap(crop, max(32, crop.width * 5), max(32, crop.height * 5), true)
            } catch (_: Exception) { null }
            if (enlarged == null) {
                try { crop.recycle() } catch (_: Exception) {}
                continue
            }

            try {
                val text = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(enlarged, 0)),
                    1200,
                    TimeUnit.MILLISECONDS
                ).text.trim()
                val digit = parseStrictDigit(text)
                if (digit != null) guesses += digit
            } catch (_: Exception) {
            } finally {
                try { enlarged.recycle() } catch (_: Exception) {}
                try { crop.recycle() } catch (_: Exception) {}
            }
        }

        if (guesses.size < 2) return 0
        val counts = guesses.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return 0
        return if (best.value >= 2) best.key else 0
    }

    private fun parseStrictDigit(raw: String): Int? {
        val s = raw.trim()
        if (s.length != 1) return null
        val c = s[0]
        return if (c in '1'..'5') c.digitToInt() else null
    }

    private fun classifyResource(bitmap: Bitmap, b: Badge): String {
        var food = 0; var gold = 0; var wood = 0; var stone = 0; var ore = 0
        val l = max(0, b.cx - max(90, b.width * 4))
        val r = min(bitmap.width - 1, b.cx - max(8, b.width / 5))
        val t = max(0, b.cy - max(70, b.height * 4))
        val bot = min(bitmap.height - 1, b.cy + max(15, b.height))
        if (r <= l || bot <= t) return "Other"

        var samples = 0
        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val p = bitmap.getPixel(x, y)
                val rr = Color.red(p); val gg = Color.green(p); val bb = Color.blue(p)
                if (!(bb > rr * 1.20f && bb > 100)) {
                    samples++
                    val sat = maxOf(rr, gg, bb) - minOf(rr, gg, bb)
                    if (rr >= 145 && gg >= 120 && bb <= 120 && rr >= gg * 0.95f && sat >= 35) food += 2
                    if (rr >= 155 && gg >= 90 && bb <= 110 && rr > gg * 1.05f && sat >= 45) gold += 2
                    if (rr in 65..195 && gg in 30..135 && bb <= 95 && rr > gg * 1.12f && sat >= 30) wood += 2
                    if (rr in 65..215 && gg in 65..215 && bb in 65..215 && abs(rr-gg) <= 28 && abs(gg-bb) <= 28 && sat <= 36) stone += 3
                    if (sat >= 45 && ((bb > rr * 1.06f && bb >= gg * 0.96f) || (rr > bb * 0.85f && bb > gg * 1.05f))) ore += 3
                }
                x += max(2, b.width / 3)
            }
            y += max(2, b.height / 3)
        }
        if (samples < 15) return "Other"
        val scores = linkedMapOf("Food" to food, "Gold" to gold, "Wood" to wood, "Stone" to stone, "Ore" to ore)
        val best = scores.maxByOrNull { it.value } ?: return "Other"
        val second = scores.values.sortedDescending().getOrNull(1) ?: 0
        if (best.value <= 0 || (second > 0 && best.value < second * 1.10f)) return "Other"
        return best.key
    }

    private fun resourceConfidence(bitmap: Bitmap, b: Badge, type: String): Int {
        if (type == "Other") return 0
        var total = 0; var match = 0
        val l = max(0, b.cx - max(82, b.width * 3))
        val r = min(bitmap.width - 1, b.cx - max(8, b.width / 5))
        val t = max(0, b.cy - max(62, b.height * 3))
        val bot = min(bitmap.height - 1, b.cy + max(12, b.height))
        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val p = bitmap.getPixel(x, y)
                val rr = Color.red(p); val gg = Color.green(p); val bb = Color.blue(p)
                val sat = maxOf(rr, gg, bb) - minOf(rr, gg, bb)
                if (!(bb > rr * 1.20f && bb > 100)) {
                    total++
                    val ok = when (type) {
                        "Food" -> rr >= 145 && gg >= 120 && bb <= 125 && sat >= 30
                        "Gold" -> rr >= 150 && gg >= 85 && bb <= 120 && rr > gg * 1.05f && sat >= 40
                        "Wood" -> rr >= 65 && gg >= 30 && bb <= 100 && rr > gg * 1.10f && sat >= 28
                        "Stone" -> rr in 65..220 && gg in 65..220 && bb in 65..220 && abs(rr-gg) <= 30 && abs(gg-bb) <= 30 && sat <= 40
                        "Ore" -> sat >= 42 && ((bb > rr * 1.04f && bb >= gg * 0.95f) || (rr > bb * 0.84f && bb > gg * 1.03f))
                        else -> false
                    }
                    if (ok) match++
                }
                x += 3
            }
            y += 3
        }
        if (total < 20) return 0
        return (match * 100 / total).coerceIn(0, 100)
    }

    private fun flagScore(bitmap: Bitmap, b: Badge): Int {
        var red = 0; var samples = 0
        val rx = max(14, b.width * 4)
        val ry = max(14, b.height * 5)
        val l = max(0, b.cx - rx); val r = min(bitmap.width - 1, b.cx + rx)
        val t = max(0, b.cy - ry); val bot = min(bitmap.height - 1, b.cy + ry)
        var y = t
        while (y <= bot) {
            var x = l
            while (x <= r) {
                val dx = x - b.cx; val dy = y - b.cy
                if (dx * dx + dy * dy <= rx * rx) {
                    val p = bitmap.getPixel(x, y)
                    val rr = Color.red(p); val gg = Color.green(p); val bb = Color.blue(p)
                    samples++
                    if (rr > 170 && rr > gg * 1.30f && rr > bb * 1.20f) red += 2
                    else if (rr > 140 && rr > gg * 1.18f && rr > bb * 1.10f) red++
                }
                x += max(2, b.width / 3)
            }
            y += max(2, b.height / 3)
        }
        return if (samples == 0) 0 else min(100, red * 100 / max(1, samples / 5))
    }

    /* Read the map-center coordinate displayed by Lords Mobile at the top. */
    private fun readMapAnchor(bitmap: Bitmap): MapAnchor? {
        val w = bitmap.width; val h = bitmap.height
        val l = (w * 0.43f).toInt().coerceAtLeast(0)
        val r = (w * 0.68f).toInt().coerceAtMost(w)
        val t = (h * 0.03f).toInt().coerceAtLeast(0)
        val bot = (h * 0.20f).toInt().coerceAtMost(h)
        if (r <= l || bot <= t) return null
        val crop = try { Bitmap.createBitmap(bitmap, l, t, r-l, bot-t) } catch (_: Exception) { null } ?: return null
        val scaled = try { Bitmap.createScaledBitmap(crop, (crop.width * 3).coerceAtLeast(300), (crop.height * 3).coerceAtLeast(120), true) } catch (_: Exception) { null }
        if (scaled == null) { try { crop.recycle() } catch (_: Exception) {}; return null }
        return try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0)), 1500, TimeUnit.MILLISECONDS).text
            val compact = text.uppercase(Locale.US).replace(" ", "")
            val xMatch = Regex("X[:=]([0-9]{1,3})").find(compact)
            val yMatch = Regex("Y[:=]([0-9]{1,3})").find(compact)
            val x = xMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val y = yMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (x != null && y != null && x in 0..511 && y in 0..511) MapAnchor(x, y) else null
        } catch (_: Exception) {
            null
        } finally {
            try { scaled.recycle() } catch (_: Exception) {}
            try { crop.recycle() } catch (_: Exception) {}
        }
    }

    private fun typePriority(type: String): Int = when (type) {
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
            val idx = out.indexOfFirst { abs(it.sx - c.sx) < max(28, c.sx / 100) && abs(it.sy - c.sy) < max(28, c.sy / 100) }
            if (idx < 0) out += c else if (c.score > out[idx].score) out[idx] = c
        }
        return out
    }

    private fun publish(n: Int, bitmap: Bitmap, list: List<Candidate>, anchor: MapAnchor?, badgeCount: Int) {
        val out = StringBuilder()
        out.append("Scan #").append(n).append('\n')
        out.append("SCREEN: ").append(bitmap.width).append('x').append(bitmap.height).append('\n')
        out.append("BLUE BADGES: ").append(badgeCount).append('\n')
        out.append("RSS VERIFIED: ").append(list.size).append('\n')
        out.append("EMPTY: ").append(list.count { !it.flagged }).append('\n')
        out.append("FLAGGED/OCCUPIED: ").append(list.count { it.flagged }).append('\n')
        out.append("LEVELS: L1=").append(list.count { it.level == 1 })
            .append(" L2=").append(list.count { it.level == 2 })
            .append(" L3=").append(list.count { it.level == 3 })
            .append(" L4=").append(list.count { it.level == 4 })
            .append(" L5=").append(list.count { it.level == 5 }).append("\n")
        if (anchor != null) out.append("MAP CENTER: X=").append(anchor.x).append(" Y=").append(anchor.y).append('\n')
        else out.append("MAP CENTER: not read\n")

        out.append("\nVERIFIED RSS:\n")
        list.take(maxResults).forEachIndexed { i, c ->
            out.append(String.format(Locale.US, "%2d. %-5s Lv%d screen(%d,%d) C%d F%d", i+1, c.type, c.level, c.sx, c.sy, c.confidence, c.flagScore))
            if (c.flagged) out.append(" [FLAGGED]")
            out.append('\n')
        }

        out.append("\nKINGDOM X/Y:\n")
        out.append("Not guessed. A map-scale calibration is required to convert screen position to exact kingdom X/Y.\n")
        out.append("The scanner DOES read the visible map-center X/Y when OCR can verify it.\n")
        out.append("Zoom-adaptive: ON\n")
        out.append("LEVEL RANGE: 1-5 ONLY\n")
        out.append("NO TROOP SENT")
        setStatus(out.toString())
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
        overlay = null; params = null; status = null; startButton = null; wm = null
        super.onDestroy()
    }
}
