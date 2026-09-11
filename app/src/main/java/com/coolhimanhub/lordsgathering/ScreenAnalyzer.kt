package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V30 — vision-only RSS candidate detector calibrated against the supplied
 * 1536x707 Lords Mobile gameplay frames.
 *
 * The map screenshot is used only to nominate a resource-tile candidate.
 * Resource type/level/occupancy are NOT guessed from artwork. The selected
 * candidate is opened once and the in-game panel is the authority before a
 * Gather action is allowed.
 *
 * Calibration result on the supplied frames:
 *   - 8 genuine blue level badges retained
 *   - the 31x31 Transformers artwork/icon false positive rejected
 *   - candidate probe points remain on the resource artwork, not the badge
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

        // The assistant overlay occupies the left portion of the supplied
        // frames. Start just outside it, while still retaining badges that are
        // close to its right edge. Bottom/right game controls are excluded.
        val left=max(400,expectedRegionX.first)
        val right=min(bitmap.width-100,expectedRegionX.last)
        val top=max(60,expectedRegionY.first)
        val bottom=min(bitmap.height-110,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        return findBadges(bitmap,left,right,top,bottom).map { b ->
            // Blue level badges sit lower/right of the resource artwork. Probe
            // toward the artwork so a future verification tap never targets
            // the white digit itself.
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
     *
     * V29's bounds were broad enough to admit the Transformers artwork/icon,
     * which happened to contain a large blue connected region. Real level
     * badges in the supplied frames are compact trapezoids, approximately
     * 20–40 px wide and 16–28 px high. These tighter geometry limits remove
     * that false positive without collapsing neighbouring resource badges.
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
            if(area !in 180..650)continue
            if(bw !in 20..40 || bh !in 16..28)continue

            val aspect=bw.toFloat()/bh.toFloat()
            if(aspect !in 0.95f..2.40f)continue

            val density=(area.toFloat()/(bw*bh)).coerceIn(0f,1f)
            if(density<0.40f || density>0.92f)continue

            val aspectScore=(1f-abs(aspect-1.35f)/1.35f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.62f)/0.62f).coerceIn(0f,1f)
            val areaScore=(1f-abs(area-380f)/380f).coerceIn(0f,1f)
            val confidence=(68f+10f*aspectScore+9f*densityScore+9f*areaScore)
                .toInt().coerceIn(68,96)
            found+=Badge(BoundingBox(minX,minY,maxX,maxY),confidence)
        }

        // De-duplicate only overlapping/near-identical detections. Nearby
        // resource badges are allowed to remain separate in dense scenes.
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
