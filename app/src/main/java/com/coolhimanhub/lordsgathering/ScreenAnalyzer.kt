package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V34 — simulation-calibrated, badge-first RSS candidate detector.
 *
 * The detector only nominates the small blue level badge. It deliberately does
 * not infer resource type, level or occupancy from map artwork. Those facts
 * must be verified from the opened tile panel before Gather is allowed.
 *
 * V34 additionally performs focused X/Y OCR during the same screenshot
 * analysis pass. This avoids relying on full-frame OCR to find the tiny map
 * coordinate HUD while keeping the existing GatheringAccessibilityService
 * flow and its X/Y change gate intact.
 */
class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int) {
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
        val dominantColor:Int,
        val moving:Boolean=false,
        val movingScore:Int=0
    )

    private data class Badge(val box:BoundingBox,val confidence:Int)

    companion object {
        private const val BLUE_MIN = 85
        private const val BLUE_DELTA_R = 25
        private const val BLUE_DELTA_G = 8
        private const val MIN_MAP_X = 410
        private const val MAX_MAP_X = 1420
        private const val MIN_MAP_Y = 90
        private const val MAX_BOTTOM_MARGIN = 42
    }

    fun analyzeScreenshot(
        bitmap:Bitmap,
        expectedRegionX:IntRange=0 until bitmap.width,
        expectedRegionY:IntRange=0 until bitmap.height
    ):List<RssDetection>{
        if(bitmap.width<600 || bitmap.height<400) return emptyList()

        // V34: read the coordinate HUD from focused crops while the screenshot
        // is already on the analysis executor. The result is cached for the
        // existing service callback, which then performs the normal viewport
        // change validation.
        val viewportRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ViewportOcrCache.set(ViewportOcrReader.read(bitmap, viewportRecognizer))
        } catch (_: Exception) {
            ViewportOcrCache.clear()
        } finally {
            try { viewportRecognizer.close() } catch (_: Exception) {}
        }

        // Calibrated against the supplied 1536x707 gameplay frames. Keep the
        // broad map area while excluding the assistant overlay and fixed right
        // controls. Lower-map badges remain inspectable.
        val left=max(MIN_MAP_X, expectedRegionX.first)
        val right=min(MAX_MAP_X, min(bitmap.width-1, expectedRegionX.last))
        val top=max(MIN_MAP_Y, expectedRegionY.first)
        val bottom=min(bitmap.height-MAX_BOTTOM_MARGIN, expectedRegionY.last)
        if(right<=left || bottom<=top) return emptyList()

        return findBadges(bitmap,left,right,top,bottom).map { badge ->
            // The badge is normally down/right of the resource artwork. Shift
            // the probe onto the artwork, never underneath the overlay.
            val probeX=(badge.box.centerX-18).coerceIn(left+8,right-8)
            val probeY=(badge.box.centerY-7).coerceIn(top+8,bottom-8)
            RssDetection(
                type="RSS?",
                level=0,
                centerX=probeX,
                centerY=probeY,
                boundingBox=badge.box,
                confidence=badge.confidence,
                occupied=false,
                dominantColor=Color.TRANSPARENT
            )
        }
    }

    /**
     * Finds compact blue level badges. A 3x3 connectivity bridge closes the
     * small white/anti-aliased holes caused by the badge level glyph.
     */
    private fun findBadges(
        bitmap:Bitmap,
        left:Int,
        right:Int,
        top:Int,
        bottom:Int
    ):List<Badge>{
        val width=right-left+1
        val height=bottom-top+1
        val visited=BooleanArray(width*height)
        val queue=IntArray(width*height)
        val found=ArrayList<Badge>()

        fun originalBlueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            return b>=BLUE_MIN && b-r>=BLUE_DELTA_R && b-g>=BLUE_DELTA_G && r<=145
        }

        // Treat a pixel as connected when a real blue pixel is present in its
        // immediate 3x3 neighbourhood. This bridges the white digit gap while
        // avoiding the larger expansion of a 5x5/7x7 dilation.
        fun connectedBlueAt(x:Int,y:Int):Boolean{
            for(dy in -1..1) for(dx in -1..1){
                val nx=x+dx; val ny=y+dy
                if(nx in left..right && ny in top..bottom && originalBlueAt(nx,ny)) return true
            }
            return false
        }

        for(y in top..bottom) for(x in left..right){
            val local=(y-top)*width+(x-left)
            if(visited[local]) continue
            if(!connectedBlueAt(x,y)){ visited[local]=true; continue }

            var head=0; var tail=0
            queue[tail++]=local; visited[local]=true
            var minX=x; var maxX=x; var minY=y; var maxY=y

            while(head<tail){
                val p=queue[head++]
                val py=p/width+top; val px=p%width+left
                minX=min(minX,px); maxX=max(maxX,px)
                minY=min(minY,py); maxY=max(maxY,py)
                for(dy in -1..1) for(dx in -1..1){
                    if(dx==0 && dy==0) continue
                    val nx=px+dx; val ny=py+dy
                    if(nx !in left..right || ny !in top..bottom) continue
                    val ni=(ny-top)*width+(nx-left)
                    if(visited[ni]) continue
                    visited[ni]=true
                    if(connectedBlueAt(nx,ny)) queue[tail++]=ni
                }
            }

            val bw=maxX-minX+1
            val bh=maxY-minY+1
            if(bw !in 18..45 || bh !in 16..35) continue

            var originalArea=0
            var whiteGlyphPixels=0
            for(py in minY..maxY) for(px in minX..maxX){
                if(originalBlueAt(px,py)) originalArea++
                val c=bitmap.getPixel(px,py)
                if(Color.red(c)>=185 && Color.green(c)>=185 && Color.blue(c)>=185) whiteGlyphPixels++
            }

            val density=originalArea.toFloat()/(bw*bh)
            val aspect=bw.toFloat()/bh.toFloat()
            if(originalArea !in 160..700) continue
            if(density<0.30f || density>0.82f) continue
            if(whiteGlyphPixels<8) continue

            // Transformer/game-header artwork can contain a badge-shaped blue
            // patch near the top-left edge. Reject only that known false-positive
            // zone rather than globally shrinking the inspection area.
            if(maxX<500 && minY<150) continue

            val aspectScore=(1f-abs(aspect-1.35f)/1.55f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.52f)/0.52f).coerceIn(0f,1f)
            val glyphScore=(whiteGlyphPixels.coerceAtMost(80)/80f)
            val confidence=(68f + 10f*aspectScore + 9f*densityScore + 5f*glyphScore)
                .toInt().coerceIn(68,92)

            found += Badge(BoundingBox(minX,minY,maxX,maxY),confidence)
        }

        // Deduplicate fragments produced at badge/art edges.
        val sorted=found.sortedWith(
            compareByDescending<Badge>{it.confidence}
                .thenBy{it.box.centerY}
                .thenBy{it.box.centerX}
        )
        val out=ArrayList<Badge>()
        for(b in sorted){
            val duplicate=out.any{
                val dx=abs(it.box.centerX-b.box.centerX)
                val dy=abs(it.box.centerY-b.box.centerY)
                dx<18 && dy<18
            }
            if(!duplicate) out+=b
        }
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }
}
