package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V29 — map badge detector calibrated against the supplied gameplay screenshots.
 *
 * Important design rule: this class only proposes a probe point. It does NOT
 * infer RSS type/level from artwork. The opened in-game tile panel remains the
 * authority before any Gather action is permitted.
 *
 * V28 used a 2x down-sampled connected-component search. On the supplied
 * 1536x707 gameplay frame that was too permissive in some places and too
 * destructive in others, causing valid blue level badges to collapse into a
 * single candidate. V29 therefore works at full resolution and scores the
 * compact dark-blue badge geometry directly.
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

    fun analyzeScreenshot(
        bitmap:Bitmap,
        expectedRegionX:IntRange=0 until bitmap.width,
        expectedRegionY:IntRange=0 until bitmap.height
    ):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400)return emptyList()

        // The left side is covered by the assistant overlay during normal use;
        // the far right/bottom contain game controls. Keep this proportional to
        // the actual frame rather than assuming one fixed phone resolution.
        val left=max(320,expectedRegionX.first)
        val right=min(bitmap.width-100,expectedRegionX.last)
        val top=max(60,expectedRegionY.first)
        val bottom=min(bitmap.height-110,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        return findBadges(bitmap,left,right,top,bottom)
            .map { b ->
                // In the Lords Mobile map the blue level badge is normally on
                // the lower/right side of its resource artwork. Probe slightly
                // up/left so the tap lands on the resource, not the badge text.
                val probeX=(b.box.centerX-18).coerceIn(left+8,right-8)
                val probeY=(b.box.centerY-7).coerceIn(top+8,bottom-8)
                RssDetection(
                    type="RSS?",
                    level=0,
                    centerX=probeX,
                    centerY=probeY,
                    boundingBox=b.box,
                    confidence=b.confidence,
                    occupied=false,
                    dominantColor=Color.TRANSPARENT
                )
            }
    }

    /**
     * Detect compact dark-blue level badges at full resolution.
     * The badge contains white text, so the blue connected component itself is
     * smaller than the complete visual badge; geometry is therefore part of
     * the score instead of relying on a single pixel-area threshold.
     */
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val width=right-left+1
        val height=bottom-top+1
        val visited=BooleanArray(width*height)
        val queue=IntArray(width*height)
        val found=ArrayList<Badge>()

        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            // Calibrated to the dark/medium blue level badges visible in the
            // supplied frames. White digits fail the r/g/b dominance tests.
            return b>=85 && b-r>=25 && b-g>=8 && r<=135
        }

        for(y in top..bottom) for(x in left..right){
            val local=(y-top)*width+(x-left)
            if(visited[local])continue
            if(!blueAt(x,y)){visited[local]=true;continue}

            var head=0;var tail=0
            queue[tail++]=local;visited[local]=true
            var minX=x;var maxX=x;var minY=y;var maxY=y;var area=0

            while(head<tail){
                val p=queue[head++]
                val py=p/width+top; val px=p%width+left
                area++
                minX=min(minX,px);maxX=max(maxX,px)
                minY=min(minY,py);maxY=max(maxY,py)
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy
                    if(nx !in left..right||ny !in top..bottom)continue
                    val ni=(ny-top)*width+(nx-left)
                    if(visited[ni])continue
                    visited[ni]=true
                    if(blueAt(nx,ny))queue[tail++]=ni
                }
            }

            val bw=maxX-minX+1
            val bh=maxY-minY+1
            if(area !in 120..900)continue
            if(bw !in 18..55 || bh !in 12..38)continue

            val aspect=bw.toFloat()/bh.toFloat()
            if(aspect !in 0.80f..2.60f)continue

            // Reject long UI strips / blue decorative elements. Genuine level
            // badges have substantial blue fill but are not almost solid.
            val density=(area.toFloat()/(bw*bh)).coerceIn(0f,1f)
            if(density<0.35f || density>0.92f)continue

            val aspectScore=(1f-abs(aspect-1.35f)/1.35f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.62f)/0.62f).coerceIn(0f,1f)
            val areaScore=(1f-abs(area-380f)/380f).coerceIn(0f,1f)
            val confidence=(68f+10f*aspectScore+9f*densityScore+9f*areaScore)
                .toInt().coerceIn(68,96)
            found+=Badge(BoundingBox(minX,minY,maxX,maxY),confidence)
        }

        // De-duplicate only overlapping/near-identical detections. Do not use
        // the old 18px global proximity rule because nearby resource badges can
        // legitimately be closer than that in dense map scenes.
        val out=ArrayList<Badge>()
        for(b in found.sortedByDescending{it.confidence}){
            val duplicate=out.any{
                val dx=abs(it.box.centerX-b.box.centerX)
                val dy=abs(it.box.centerY-b.box.centerY)
                dx<12 && dy<12
            }
            if(!duplicate)out+=b
        }
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }
}
