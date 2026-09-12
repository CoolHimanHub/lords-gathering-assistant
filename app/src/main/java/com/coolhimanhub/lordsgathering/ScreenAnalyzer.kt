package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** V39: resolution-aware RSS detection with game-coordinate support.
 *
 * The live 2756x1268 recording exposed a V38 validation bug: the white level
 * number is drawn inside/over the blue badge but is not itself a blue pixel.
 * V38 therefore counted white glyph pixels only while traversing the blue
 * component and rejected valid badges. V39 validates the glyph in the badge
 * bounding box instead. The 3x3 dilation was also removed because it multiplied
 * work on roughly 1.8M pixels without being necessary for the game's badges.
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

    private val viewportRecognizer:TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val REF_W=1536f
        private const val REF_H=707f
        private const val REF_MAP_LEFT=410f
        private const val REF_MAP_RIGHT=1420f
        private const val REF_MAP_TOP=90f
        private const val REF_BOTTOM_MARGIN=42f
        private const val BLUE_MIN=85
        private const val BLUE_DELTA_R=25
        private const val BLUE_DELTA_G=8
        private const val RED_MAX=145
    }

    fun analyzeScreenshot(
        bitmap:Bitmap,
        expectedRegionX:IntRange=0 until bitmap.width,
        expectedRegionY:IntRange=0 until bitmap.height
    ):List<RssDetection>{
        if(bitmap.width<600 || bitmap.height<400){
            ViewportOcrCache.clear()
            return emptyList()
        }

        // Coordinate OCR and badge detection use the exact same screenshot.
        try{
            ViewportOcrCache.set(ViewportOcrReader.read(bitmap,viewportRecognizer))
        }catch(_:Exception){
            ViewportOcrCache.clear()
        }

        val sx=bitmap.width/REF_W
        val sy=bitmap.height/REF_H
        val left=max((REF_MAP_LEFT*sx).toInt(),expectedRegionX.first)
        val right=min((REF_MAP_RIGHT*sx).toInt(),min(bitmap.width-1,expectedRegionX.last))
        val top=max((REF_MAP_TOP*sy).toInt(),expectedRegionY.first)
        val bottom=min(bitmap.height-1-(REF_BOTTOM_MARGIN*sy).toInt(),expectedRegionY.last)
        if(right<=left || bottom<=top)return emptyList()

        return findBadges(bitmap,left,right,top,bottom,sx,sy).map{badge->
            // The blue level badge is normally down/right of the resource art.
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

        // Direct 8-connected blue components. No full-frame dilation pass.
        val blue=BooleanArray(size)
        var i=0
        while(i<size){
            val c=pixels[i]
            val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            blue[i]=b>=BLUE_MIN && b-r>=BLUE_DELTA_R && b-g>=BLUE_DELTA_G && r<=RED_MAX
            i++
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

        for(y in 0 until height)for(x in 0 until width){
            val start=y*width+x
            if(visited[start]||!blue[start])continue
            var head=0;var tail=0
            queue[tail++]=start;visited[start]=true
            var minX=x;var maxX=x;var minY=y;var maxY=y
            var originalArea=0

            while(head<tail){
                val p=queue[head++]
                val py=p/width;val px=p%width
                minX=min(minX,px);maxX=max(maxX,px)
                minY=min(minY,py);maxY=max(maxY,py)
                originalArea++
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy
                    if(nx !in 0 until width||ny !in 0 until height)continue
                    val ni=ny*width+nx
                    if(!visited[ni]&&blue[ni]){visited[ni]=true;queue[tail++]=ni}
                }
            }

            val bw=maxX-minX+1;val bh=maxY-minY+1
            if(bw !in minW..maxW||bh !in minH..maxH)continue
            if(originalArea !in minArea..maxArea)continue
            val density=originalArea.toFloat()/(bw*bh).toFloat()
            if(density<0.30f||density>0.82f)continue

            // IMPORTANT: the white level glyph is not blue, so inspect a small
            // padded region around the blue component instead of the component
            // mask itself. This is the V38 false-negative fix.
            val padX=max(4,(4f*sx).toInt())
            val padY=max(4,(4f*sy).toInt())
            val checkLeft=max(0,minX-padX)
            val checkTop=max(0,minY-padY)
            val checkRight=min(width-1,maxX+padX)
            val checkBottom=min(height-1,maxY+padY)
            var whiteGlyphPixels=0
            var cy=checkTop
            while(cy<=checkBottom){
                var cx=checkLeft
                while(cx<=checkRight){
                    val c=pixels[cy*width+cx]
                    if(Color.red(c)>=185&&Color.green(c)>=185&&Color.blue(c)>=185)whiteGlyphPixels++
                    cx++
                }
                cy++
            }
            if(whiteGlyphPixels<8)continue

            val absoluteMinX=left+minX
            val absoluteMinY=top+minY
            if(absoluteMinX<(500f*sx).toInt()&&absoluteMinY<(150f*sy).toInt())continue

            val aspect=bw.toFloat()/bh.toFloat()
            val aspectScore=(1f-abs(aspect-1.35f)/1.55f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.52f)/0.52f).coerceIn(0f,1f)
            val glyphScore=(whiteGlyphPixels.coerceAtMost(160)/160f)
            val confidence=(68f+10f*aspectScore+9f*densityScore+5f*glyphScore)
                .toInt().coerceIn(68,92)
            found+=Badge(BoundingBox(absoluteMinX,absoluteMinY,left+maxX,top+maxY),confidence)
        }

        val sorted=found.sortedWith(
            compareByDescending<Badge>{it.confidence}.thenBy{it.box.centerY}.thenBy{it.box.centerX}
        )
        val out=ArrayList<Badge>()
        val mergeX=max(24,(18f*sx).toInt())
        val mergeY=max(20,(18f*sy).toInt())
        for(b in sorted){
            val duplicate=out.any{
                abs(it.box.centerX-b.box.centerX)<mergeX&&
                    abs(it.box.centerY-b.box.centerY)<mergeY
            }
            if(!duplicate)out+=b
        }
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }

    fun close(){
        try{viewportRecognizer.close()}catch(_:Exception){}
        ViewportOcrCache.clear()
    }
}
