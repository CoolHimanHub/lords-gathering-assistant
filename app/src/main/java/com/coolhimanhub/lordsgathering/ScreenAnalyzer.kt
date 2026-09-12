package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V37 fast, resolution-aware map vision.
 *
 * Calibrated from the live recordings as well as the earlier 1536x707 frames.
 * The phone now captures 2756x1268 frames, so fixed pixel limits from V34 were
 * only inspecting roughly the left half of the real map. V37 scales the ROI and
 * badge geometry from the 1536x707 reference instead.
 *
 * Viewport OCR is performed once, on the same captured bitmap, using a focused
 * crop. The service no longer performs a second full-screen OCR pass.
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

    private val viewportRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        // Reference capture used by the earlier calibrated detector.
        private const val REF_W = 1536f
        private const val REF_H = 707f
        private const val REF_MAP_LEFT = 410f
        private const val REF_MAP_RIGHT = 1420f
        private const val REF_MAP_TOP = 90f
        private const val REF_BOTTOM_MARGIN = 42f

        private const val BLUE_MIN = 85
        private const val BLUE_DELTA_R = 25
        private const val BLUE_DELTA_G = 8
        private const val RED_MAX = 145
    }

    fun analyzeScreenshot(
        bitmap:Bitmap,
        expectedRegionX:IntRange=0 until bitmap.width,
        expectedRegionY:IntRange=0 until bitmap.height
    ):List<RssDetection>{
        if(bitmap.width<600 || bitmap.height<400) {
            ViewportOcrCache.clear()
            return emptyList()
        }

        // One focused OCR operation per screenshot. It is deliberately kept
        // separate from the badge detector so an OCR failure cannot invent a
        // candidate and a badge failure cannot invent a viewport.
        try {
            ViewportOcrCache.set(ViewportOcrReader.read(bitmap, viewportRecognizer))
        } catch (_: Exception) {
            ViewportOcrCache.clear()
        }

        val sx=bitmap.width/REF_W
        val sy=bitmap.height/REF_H
        val left=max((REF_MAP_LEFT*sx).toInt(), expectedRegionX.first)
        val right=min((REF_MAP_RIGHT*sx).toInt(), min(bitmap.width-1, expectedRegionX.last))
        val top=max((REF_MAP_TOP*sy).toInt(), expectedRegionY.first)
        val bottom=min(bitmap.height-1-(REF_BOTTOM_MARGIN*sy).toInt(), expectedRegionY.last)
        if(right<=left || bottom<=top) return emptyList()

        return findBadges(bitmap,left,right,top,bottom,sx,sy).map { badge ->
            // Badge normally sits down/right of the resource artwork.
            val probeX=(badge.box.centerX-(18f*sx).toInt()).coerceIn(left+8,right-8)
            val probeY=(badge.box.centerY-(7f*sy).toInt()).coerceIn(top+8,bottom-8)
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
     * Bulk-pixel badge detection. The previous implementation called
     * Bitmap.getPixel() repeatedly and performed a 3x3 neighbourhood query for
     * almost every pixel. V37 reads the bitmap once into an IntArray and builds
     * a compact 8-connected blue mask, which is substantially cheaper.
     */
    private fun findBadges(
        bitmap:Bitmap,
        left:Int,
        right:Int,
        top:Int,
        bottom:Int,
        sx:Float,
        sy:Float
    ):List<Badge>{
        val width=right-left+1
        val height=bottom-top+1
        val size=width*height
        val pixels=IntArray(size)
        bitmap.getPixels(pixels,0,width,left,top,width,height)

        val blue=BooleanArray(size)
        var i=0
        while(i<size){
            val c=pixels[i]
            val r=Color.red(c)
            val g=Color.green(c)
            val b=Color.blue(c)
            blue[i]=b>=BLUE_MIN && b-r>=BLUE_DELTA_R && b-g>=BLUE_DELTA_G && r<=RED_MAX
            i++
        }

        // A single 3x3 dilation bridges anti-aliased badge edges. We keep the
        // original blue area separately so confidence is based on real pixels.
        val connected=BooleanArray(size)
        for(y in 0 until height){
            val row=y*width
            for(x in 0 until width){
                var hit=false
                loop@ for(dy in -1..1) for(dx in -1..1){
                    val nx=x+dx; val ny=y+dy
                    if(nx in 0 until width && ny in 0 until height && blue[ny*width+nx]) {
                        hit=true; break@loop
                    }
                }
                connected[row+x]=hit
            }
        }

        val visited=BooleanArray(size)
        val queue=IntArray(size)
        val found=ArrayList<Badge>()
        val minW=max(12,(18f*sx).toInt())
        val maxW=max(minW+1,(45f*sx).toInt())
        val minH=max(10,(16f*sy).toInt())
        val maxH=max(minH+1,(35f*sy).toInt())
        val minArea=max(80,(160f*sx*sy).toInt())
        val maxArea=max(minArea+1,(700f*sx*sy).toInt())

        for(y in 0 until height) for(x in 0 until width){
            val start=y*width+x
            if(visited[start] || !connected[start]) continue
            var head=0; var tail=0
            queue[tail++]=start; visited[start]=true
            var minX=x; var maxX=x; var minY=y; var maxY=y
            var originalArea=0
            var whiteGlyphPixels=0

            while(head<tail){
                val p=queue[head++]
                val py=p/width; val px=p%width
                minX=min(minX,px); maxX=max(maxX,px)
                minY=min(minY,py); maxY=max(maxY,py)
                if(blue[p]) originalArea++
                val c=pixels[p]
                if(Color.red(c)>=185 && Color.green(c)>=185 && Color.blue(c)>=185) whiteGlyphPixels++

                for(dy in -1..1) for(dx in -1..1){
                    if(dx==0 && dy==0) continue
                    val nx=px+dx; val ny=py+dy
                    if(nx !in 0 until width || ny !in 0 until height) continue
                    val ni=ny*width+nx
                    if(!visited[ni] && connected[ni]){
                        visited[ni]=true
                        queue[tail++]=ni
                    }
                }
            }

            val bw=maxX-minX+1
            val bh=maxY-minY+1
            val boxArea=bw*bh
            if(bw !in minW..maxW || bh !in minH..maxH) continue
            if(originalArea !in minArea..maxArea) continue
            val density=originalArea.toFloat()/boxArea.toFloat()
            if(density<0.30f || density>0.82f) continue
            if(whiteGlyphPixels<8) continue

            val absoluteMinX=left+minX
            val absoluteMinY=top+minY
            // Exclude the fixed game/header artwork zone only at reference scale.
            // This preserves the upper map while avoiding a known false-positive.
            if(absoluteMinX < (500f*sx).toInt() && absoluteMinY < (150f*sy).toInt()) continue

            val aspect=bw.toFloat()/bh.toFloat()
            val aspectScore=(1f-abs(aspect-1.35f)/1.55f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.52f)/0.52f).coerceIn(0f,1f)
            val glyphScore=(whiteGlyphPixels.coerceAtMost(80)/80f)
            val confidence=(68f+10f*aspectScore+9f*densityScore+5f*glyphScore)
                .toInt().coerceIn(68,92)

            found += Badge(
                BoundingBox(absoluteMinX,absoluteMinY,left+maxX,top+maxY),
                confidence
            )
        }

        val sorted=found.sortedWith(
            compareByDescending<Badge>{it.confidence}
                .thenBy{it.box.centerY}
                .thenBy{it.box.centerX}
        )
        val out=ArrayList<Badge>()
        val mergeX=max(24,(18f*sx).toInt())
        val mergeY=max(20,(18f*sy).toInt())
        for(b in sorted){
            val duplicate=out.any{
                abs(it.box.centerX-b.box.centerX)<mergeX &&
                    abs(it.box.centerY-b.box.centerY)<mergeY
            }
            if(!duplicate) out+=b
        }
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }

    fun close(){
        try { viewportRecognizer.close() } catch (_: Exception) {}
        ViewportOcrCache.clear()
    }
}
