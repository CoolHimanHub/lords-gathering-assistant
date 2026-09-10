package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V18 RSS detector.
 *
 * V18 changes:
 *  - finds badge candidates by LOCAL BLUE DENSITY instead of requiring one
 *    perfect connected blue component (the shield is anti-aliased in-game).
 *  - reads the white digit from a tight badge-centered crop.
 *  - searches several resource-art windows to the LEFT / UP-LEFT of the badge.
 *  - requires a real colour signature; green terrain alone is rejected.
 *  - occupation is only reported when a compact red marker exists; scattered
 *    red/orange pixels in Ore artwork do not mark a tile occupied.
 */
class ScreenAnalyzer {
    companion object {
        private const val BLUE_MIN = 78
        private const val BLUE_R_GAP = 18
        private const val BLUE_G_GAP = 4

        private const val BADGE_W_MIN = 18
        private const val BADGE_W_MAX = 52
        private const val BADGE_H_MIN = 18
        private const val BADGE_H_MAX = 50

        private val DIGITS = arrayOf(
            arrayOf("00100","01100","00100","00100","00100","00100","01110"),
            arrayOf("11100","00010","00010","00100","01000","10000","11110"),
            arrayOf("11100","00010","00010","01100","00010","00010","11100"),
            arrayOf("00100","01100","10100","10100","11110","00100","00100"),
            arrayOf("11110","10000","10000","11100","00010","00010","11100")
        )
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 100 || bitmap.height < 100) return emptyList()

        val badges = findBadgeCandidates(bitmap, expectedRegionX, expectedRegionY)
        val out = mutableListOf<RssDetection>()

        for (badge in badges) {
            val level = readBadgeLevel(bitmap, badge) ?: continue
            val art = classifyResourceArtwork(bitmap, badge) ?: continue
            val occupied = detectOccupation(bitmap, badge)
            val badgeScore = badgeConfidence(bitmap, badge)
            val confidence = (art.confidence * 0.82 + badgeScore * 0.18)
                .toInt().coerceIn(0, 100)
            if (confidence < 62) continue

            out += RssDetection(
                type = art.name,
                level = level,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = art.avg
            )
        }
        return deduplicate(out)
    }

    /**
     * A local-density detector is deliberately used instead of a single blue
     * connected component. The game's shield has a blue body, dark outline,
     * white digit and anti-aliased edges; the blue pixels are not guaranteed
     * to form one stable component at 2x sampling.
     */
    private fun findBadgeCandidates(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {
        val step = 2
        val w = bitmap.width
        val h = bitmap.height
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val blue = IntArray(gw * gh)

        fun isBlue(x: Int, y: Int): Boolean {
            if (x !in regionX || y !in regionY) return false
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return b >= BLUE_MIN && b - r >= BLUE_R_GAP && b - g >= BLUE_G_GAP && b >= g + 3
        }

        for (gy in 0 until gh) for (gx in 0 until gw) {
            blue[gy * gw + gx] = if (isBlue(gx * step, gy * step)) 1 else 0
        }

        // Integral image gives O(1) blue-density windows.
        val iw = gw + 1
        val integral = IntArray((gh + 1) * iw)
        for (y in 0 until gh) {
            var row = 0
            for (x in 0 until gw) {
                row += blue[y * gw + x]
                integral[(y + 1) * iw + (x + 1)] = integral[y * iw + (x + 1)] + row
            }
        }

        fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = max(0, x0); val b = max(0, y0)
            val c = min(gw - 1, x1); val d = min(gh - 1, y1)
            if (c < a || d < b) return 0
            return integral[(d + 1) * iw + (c + 1)] - integral[b * iw + (c + 1)] -
                integral[(d + 1) * iw + a] + integral[b * iw + a]
        }

        data class Candidate(val cx: Int, val cy: Int, val score: Int)
        val candidates = mutableListOf<Candidate>()

        // Search only the map area; exclude the fixed left control panel and bottom HUD.
        val minX = max(regionX.first, 140)
        val maxX = min(regionX.last, w - 1)
        val minY = max(regionY.first, 45)
        val maxY = min(regionY.last, h - 125)

        for (y in minY..maxY step 4) {
            for (x in minX..maxX step 4) {
                val gx = x / step
                val gy = y / step
                // A shield is roughly 24-40 px wide/high. Test a 20x24 sampled window.
                val count = sum(gx - 6, gy - 6, gx + 7, gy + 7)
                if (count < 24 || count > 145) continue

                val left = x - 26; val right = x + 26
                val top = y - 26; val bottom = y + 26
                var white = 0
                var blueNear = 0
                for (yy in max(0, top)..min(h - 1, bottom) step 3) {
                    for (xx in max(0, left)..min(w - 1, right) step 3) {
                        val c = bitmap.getPixel(xx, yy)
                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                        if (b >= BLUE_MIN && b - r >= BLUE_R_GAP && b - g >= BLUE_G_GAP) blueNear++
                        if (r >= 165 && g >= 165 && b >= 165 && max(r,max(g,b))-min(r,min(g,b)) < 70) white++
                    }
                }
                if (blueNear < 10 || white < 2) continue
                candidates += Candidate(x, y, count * 3 + blueNear * 2 + white)
            }
        }

        // Non-max suppression. Keep one center for each physical shield.
        val selected = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (selected.none { abs(it.cx - c.cx) < 28 && abs(it.cy - c.cy) < 28 }) {
                selected += c
            }
        }

        return selected.map {
            val halfW = 19
            val halfH = 19
            BoundingBox(
                max(0, it.cx - halfW),
                max(0, it.cy - halfH),
                min(w - 1, it.cx + halfW),
                min(h - 1, it.cy + halfH)
            )
        }
    }

    private fun readBadgeLevel(bitmap: Bitmap, badge: BoundingBox): Int? {
        // The candidate box is centered on the shield; use only its central area.
        val l = max(0, badge.centerX - 12)
        val r = min(bitmap.width - 1, badge.centerX + 12)
        val t = max(0, badge.centerY - 15)
        val b = min(bitmap.height - 1, badge.centerY + 15)
        val w = r - l + 1; val h = b - t + 1
        if (w < 8 || h < 10) return null

        val white = Array(h) { BooleanArray(w) }
        for (yy in 0 until h) for (xx in 0 until w) {
            val c = bitmap.getPixel(l + xx, t + yy)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            val mn = min(rr, min(gg, bb)); val mx = max(rr, max(gg, bb))
            white[yy][xx] = mn >= 145 && mx >= 175 && mx - mn <= 90
        }

        // Find the largest compact white component near the badge center.
        val seen = BooleanArray(w * h)
        data class P(val x: Int, val y: Int)
        var bestPts = emptyList<P>()
        for (yy in 0 until h) for (xx in 0 until w) {
            val idx = yy * w + xx
            if (!white[yy][xx] || seen[idx]) continue
            val q = ArrayDeque<P>()
            val pts = mutableListOf<P>()
            q.add(P(xx, yy)); seen[idx] = true
            while (q.isNotEmpty()) {
                val p = q.removeFirst(); pts += p
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = p.x + dx; val ny = p.y + dy
                    if (nx !in 0 until w || ny !in 0 until h || (dx == 0 && dy == 0)) continue
                    val ni = ny * w + nx
                    if (!seen[ni] && white[ny][nx]) { seen[ni] = true; q.add(P(nx,ny)) }
                }
            }
            val centerDist = abs((pts.map { it.x }.average()).toInt() - w / 2) +
                abs((pts.map { it.y }.average()).toInt() - h / 2)
            if (pts.size in 5..220 && (bestPts.isEmpty() || pts.size > bestPts.size ||
                    (pts.size >= bestPts.size - 3 && centerDist < 8))) bestPts = pts
        }
        if (bestPts.size < 5) return null

        val minX = bestPts.minOf { it.x }; val maxX = bestPts.maxOf { it.x }
        val minY = bestPts.minOf { it.y }; val maxY = bestPts.maxOf { it.y }
        val dw = maxX - minX + 1; val dh = maxY - minY + 1
        if (dw !in 3..18 || dh !in 8..28 || dw > dh * 1.35) return null

        val sample = Array(7) { BooleanArray(5) }
        for (gy in 0 until 7) for (gx in 0 until 5) {
            val x0 = minX + gx * dw / 5; val x1 = max(x0 + 1, minX + (gx + 1) * dw / 5)
            val y0 = minY + gy * dh / 7; val y1 = max(y0 + 1, minY + (gy + 1) * dh / 7)
            var on = 0; var total = 0
            for (yy in y0 until min(y1, h)) for (xx in x0 until min(x1, w)) {
                total++; if (white[yy][xx]) on++
            }
            sample[gy][gx] = total > 0 && on * 100 >= total * 16
        }

        var best = -1; var bestScore = Int.MAX_VALUE; var second = Int.MAX_VALUE
        for (d in DIGITS.indices) {
            var s = 0
            for (yy in 0 until 7) for (xx in 0 until 5) {
                if (sample[yy][xx] != (DIGITS[d][yy][xx] == '1')) s++
            }
            if (s < bestScore) { second = bestScore; bestScore = s; best = d + 1 }
            else if (s < second) second = s
        }
        val margin = second - bestScore
        if (best !in 1..5 || bestScore > 10 || (bestScore > 6 && margin < 2)) return null
        return best
    }

    private fun classifyResourceArtwork(bitmap: Bitmap, badge: BoundingBox): TypeResult? {
        // Actual RSS art is predominantly left/up-left of the shield.
        val windows = listOf(
            Window(badge.centerX - 100, badge.centerX - 18, badge.centerY - 62, badge.centerY + 20),
            Window(badge.centerX - 82, badge.centerX - 8, badge.centerY - 50, badge.centerY + 26),
            Window(badge.centerX - 68, badge.centerX - 2, badge.centerY - 36, badge.centerY + 34),
            Window(badge.centerX - 110, badge.centerX - 30, badge.centerY - 30, badge.centerY + 38)
        )

        var best: TypeResult? = null
        for (win in windows) {
            val r = scoreWindow(bitmap, badge, win) ?: continue
            if (best == null || r.confidence > best.confidence) best = r
        }
        return best
    }

    private fun scoreWindow(bitmap: Bitmap, badge: BoundingBox, win: Window): TypeResult? {
        val l = max(0, win.l); val r = min(bitmap.width - 1, win.r)
        val t = max(45, win.t); val b = min(bitmap.height - 126, win.b)
        if (r <= l || b <= t) return null

        var n = 0
        var brown = 0; var cyan = 0; var yellow = 0; var brightYellow = 0
        var grey = 0; var green = 0; var orange = 0; var dark = 0
        var saturated = 0
        var sr = 0L; var sg = 0L; var sb = 0L

        for (y in t..b step 2) for (x in l..r step 2) {
            if (x in badge.minX..badge.maxX && y in badge.minY..badge.maxY) continue
            val c = bitmap.getPixel(x,y)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            val mx = max(rr,max(gg,bb)); val mn = min(rr,min(gg,bb)); val spread = mx-mn
            val sat = if (mx == 0) 0f else spread.toFloat()/mx
            n++; sr += rr; sg += gg; sb += bb
            if (sat > .32f) saturated++
            if (gg > rr * 1.07 && gg > bb * 1.07 && sat > .14f) green++
            if (rr > 78 && rr > gg * 1.10 && gg > bb * 1.04) brown++
            if (rr > 112 && rr > gg * 1.16 && gg > bb * 1.10) orange++
            if (bb > rr * 1.10 && bb > gg * 1.01 && bb > 82 && sat > .18f) cyan++
            if (rr > 140 && gg > 118 && bb < gg * .86 && rr > bb * 1.20) yellow++
            if (rr > 190 && gg > 165 && bb < 130) brightYellow++
            if (spread < 42 && mx in 82..225) grey++
            if (mx < 62) dark++
        }
        if (n < 100) return null

        val avg = Triple((sr/n).toInt(), (sg/n).toInt(), (sb/n).toInt())
        val dn = n.toFloat()

        // Concentration scores. Green terrain is an explicit penalty.
        val oreRaw = cyan/dn*145f + min(22f, orange*1.0f)
        val stoneRaw = grey/dn*145f + min(18f, saturated*.25f)
        val woodRaw = brown/dn*155f + min(22f, orange*.7f) - min(30f, green*.28f)
        val foodRaw = yellow/dn*165f + min(25f, brightYellow*1.15f) - min(32f, green*.30f)
        val goldRaw = brightYellow/dn*175f + min(18f, saturated*.25f)

        val candidates = mutableListOf<Pair<String,Int>>()
        if (oreRaw >= 44 && cyan >= n*.10f) candidates += "Ore" to min(94, oreRaw.toInt()+8)
        if (stoneRaw >= 44 && grey >= n*.18f) candidates += "Stone" to min(93, stoneRaw.toInt()+7)
        if (woodRaw >= 44 && brown >= n*.08f && brown > green*.45f) candidates += "Wood" to min(93, woodRaw.toInt()+7)
        if (foodRaw >= 46 && yellow >= n*.07f && yellow > green*.38f) candidates += "Food" to min(94, foodRaw.toInt()+7)
        if (goldRaw >= 58 && brightYellow >= n*.07f) candidates += "Gold" to min(94, goldRaw.toInt()+5)
        if (candidates.isEmpty()) return null

        candidates.sortByDescending { it.second }
        val first = candidates[0]; val second = candidates.getOrNull(1)?.second ?: 0
        if (second > 0 && first.second-second < 7) return null
        if (green > n*.62f && first.first != "Wood") return null
        if (dark > n*.78f) return null

        return TypeResult(first.first, first.second.coerceIn(45,94), avg)
    }

    /**
     * Only a compact red component near the shield counts as a march marker.
     * This avoids interpreting Ore's red/orange mineral pixels as occupation.
     */
    private fun detectOccupation(bitmap: Bitmap, badge: BoundingBox): Boolean {
        val l = max(0, badge.centerX - 65); val r = min(bitmap.width-1, badge.centerX + 30)
        val t = max(45, badge.centerY - 45); val b = min(bitmap.height-126, badge.centerY + 45)
        if (r <= l || b <= t) return false

        val sw = r-l+1; val sh = b-t+1
        val red = BooleanArray(sw*sh)
        for (y in t..b) for (x in l..r) {
            val c=bitmap.getPixel(x,y); val rr=Color.red(c); val gg=Color.green(c); val bb=Color.blue(c)
            red[(y-t)*sw+(x-l)] = rr > 195 && rr > gg*1.7 && rr > bb*1.55 && gg < 125
        }
        val seen=BooleanArray(red.size)
        for (yy in 0 until sh) for (xx in 0 until sw) {
            val idx=yy*sw+xx; if(!red[idx]||seen[idx]) continue
            var count=0; var minX=xx; var maxX=xx; var minY=yy; var maxY=yy
            val q=ArrayDeque<Int>(); q.add(idx); seen[idx]=true
            while(q.isNotEmpty()){
                val p=q.removeFirst(); val py=p/sw; val px=p%sw; count++
                minX=min(minX,px); maxX=max(maxX,px); minY=min(minY,py); maxY=max(maxY,py)
                for(dy in -1..1) for(dx in -1..1){
                    if(dx==0&&dy==0) continue
                    val nx=px+dx; val ny=py+dy
                    if(nx !in 0 until sw || ny !in 0 until sh) continue
                    val ni=ny*sw+nx
                    if(!seen[ni]&&red[ni]){seen[ni]=true;q.add(ni)}
                }
            }
            val cw=maxX-minX+1; val ch=maxY-minY+1
            // Resource artwork red fragments are normally tiny. A marker is a
            // coherent blob with useful size and near-circular proportions.
            if(count in 18..500 && cw in 5..32 && ch in 5..32 && cw.toFloat()/ch in .45f..2.2f){
                val cx=l+(minX+maxX)/2; val cy=t+(minY+maxY)/2
                if(abs(cx-badge.centerX)<58 && abs(cy-badge.centerY)<42) return true
            }
        }
        return false
    }

    private fun badgeConfidence(bitmap: Bitmap, badge: BoundingBox): Int {
        var blue=0; var total=0
        for(y in badge.minY..badge.maxY step 2) for(x in badge.minX..badge.maxX step 2){
            val c=bitmap.getPixel(x,y); val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c); total++
            if(b>=BLUE_MIN && b-r>=BLUE_R_GAP && b-g>=BLUE_G_GAP) blue++
        }
        return if(total==0)0 else (blue*100/total).coerceIn(0,100)
    }

    private fun deduplicate(items: List<RssDetection>): List<RssDetection> {
        val out=mutableListOf<RssDetection>()
        for(item in items.sortedByDescending{it.confidence}){
            if(out.none{abs(it.centerX-item.centerX)<34&&abs(it.centerY-item.centerY)<34}) out+=item
        }
        return out
    }

    private data class Window(val l:Int,val r:Int,val t:Int,val b:Int)
    private data class TypeResult(val name:String,val confidence:Int,val avg:Triple<Int,Int,Int>)

    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int){
        val width:Int get()=maxX-minX+1
        val height:Int get()=maxY-minY+1
        val centerX:Int get()=(minX+maxX)/2
        val centerY:Int get()=(minY+maxY)/2
    }

    data class RssDetection(
        val type:String,
        val level:Int,
        val centerX:Int,
        val centerY:Int,
        val boundingBox:BoundingBox,
        val confidence:Int,
        val occupied:Boolean,
        val dominantColor:Triple<Int,Int,Int>
    )
}
