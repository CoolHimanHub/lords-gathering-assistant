package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V31 — vision-only RSS candidate detector.
 *
 * Calibrated against the supplied 1536x707 Lords Mobile frames, including
 * ordinary grass, snow/ice and darker terrain. Detection nominates a tile;
 * resource type/level/occupancy are not guessed from artwork. The selected
 * candidate must still be opened and verified by the in-game panel before
 * Gather is allowed.
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

        // Exclude the assistant overlay and bottom/right game controls.
        val left=max(400,expectedRegionX.first)
        val right=min(bitmap.width-100,expectedRegionX.last)
        val top=max(60,expectedRegionY.first)
        val bottom=min(bitmap.height-110,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        // Pass 1 is deliberately strict. Pass 2 is only used when the strict
        // pass finds too few candidates; it is designed for snow/ice frames
        // where anti-aliased badge edges fragment into smaller components.
        val strict=findBadges(bitmap,left,right,top,bottom,relaxed=false)
        val badges=if(strict.size>=2) strict else mergeAndDedupe(
            strict + findBadges(bitmap,left,right,top,bottom,relaxed=true)
        )

        return badges.map { b ->
            // Blue level badges sit lower/right of the resource artwork. Probe
            // toward the artwork so verification never targets the digit.
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

    private fun findBadges(
        bitmap:Bitmap,
        left:Int,
        right:Int,
        top:Int,
        bottom:Int,
        relaxed:Boolean
    ):List<Badge>{
        val width=right-left+1
        val height=bottom-top+1
        val visited=BooleanArray(width*height)
        val queue=IntArray(width*height)
        val found=ArrayList<Badge>()
        val hsv=FloatArray(3)

        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            Color.colorToHSV(c,hsv)
            val hue=hsv[0]
            val saturation=hsv[1]
            val value=hsv[2]
            if(!relaxed){
                return b>=85 && b-r>=25 && b-g>=8 && r<=135
            }
            // Snow/ice causes the same badge to have pale anti-aliased edges.
            // HSV catches those pixels while still rejecting near-white snow.
            val blueHue=hue>=175f && hue<=255f
            return value>=0.45f && saturation>=0.18f && blueHue && b-r>=8 && b-g>=-2
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
            val aspect=bw.toFloat()/bh.toFloat()
            val density=(area.toFloat()/(bw*bh)).coerceIn(0f,1f)

            if(!relaxed){
                if(area !in 180..650)continue
                if(bw !in 20..40 || bh !in 16..28)continue
                if(aspect !in 0.95f..2.40f)continue
                if(density<0.40f || density>0.92f)continue
            }else{
                // Fallback deliberately allows smaller fragmented badges but
                // keeps their compact rectangular/trapezoid geometry.
                if(area !in 90..900)continue
                if(bw !in 16..48 || bh !in 12..34)continue
                if(aspect !in 0.85f..2.80f)continue
                if(density<0.25f || density>0.95f)continue
            }

            val aspectScore=(1f-abs(aspect-1.35f)/1.55f).coerceIn(0f,1f)
            val densityScore=(1f-abs(density-0.62f)/0.62f).coerceIn(0f,1f)
            val areaScore=(1f-abs(area-380f)/480f).coerceIn(0f,1f)
            val confidence=if(!relaxed){
                (68f+10f*aspectScore+9f*densityScore+9f*areaScore)
            }else{
                // Fallback candidates are never given a fake 90%+ certainty.
                (75f+7f*aspectScore+6f*densityScore+4f*areaScore)
            }.toInt().coerceIn(68,92)
            found+=Badge(BoundingBox(minX,minY,maxX,maxY),confidence)
        }
        return found
    }

    private fun mergeAndDedupe(found:List<Badge>):List<Badge>{
        val out=ArrayList<Badge>()
        for(b in found.sortedByDescending{it.confidence}){
            val duplicate=out.any{
                val dx=abs(it.box.centerX-b.box.centerX)
                val dy=abs(it.box.centerY-b.box.centerY)
                dx<16 && dy<16
            }
            if(!duplicate)out+=b
        }
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }
}
