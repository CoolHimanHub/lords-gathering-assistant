package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V28 — badge-first candidate detector.
 *
 * The supplied gameplay recordings show that the blue level badge is the most
 * stable screen-space anchor. Artwork/type and occupancy are deliberately NOT
 * guessed from map pixels. A badge is only a probe candidate; the opened tile
 * panel is the authority for RSS type, level, occupancy and Gather availability.
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
        private const val BLUE_MIN = 70
        private const val MIN_BADGE_AREA = 35
        private const val MAX_BADGE_AREA = 250
    }

    fun analyzeScreenshot(
        bitmap:Bitmap,
        expectedRegionX:IntRange=0 until bitmap.width,
        expectedRegionY:IntRange=0 until bitmap.height
    ):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400)return emptyList()
        val left=max(110,expectedRegionX.first)
        val right=min(1285,min(bitmap.width-1,expectedRegionX.last))
        val top=max(65,expectedRegionY.first)
        val bottom=min(bitmap.height-120,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        return findBadges(bitmap,left,right,top,bottom)
            .map { b ->
                RssDetection(
                    type="RSS?",
                    level=0,
                    centerX=b.box.centerX,
                    centerY=b.box.centerY,
                    boundingBox=b.box,
                    confidence=b.confidence,
                    occupied=false,
                    dominantColor=Color.TRANSPARENT
                )
            }
    }

    /** Detect compact blue level-badge geometry without reading artwork. */
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val step=2
        val gw=(right-left)/step+1
        val gh=(bottom-top)/step+1
        val visited=BooleanArray(gw*gh)
        val queue=IntArray(gw*gh)
        val found=ArrayList<Badge>()

        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            return b>=BLUE_MIN && b-r>=12 && b>=g*0.94f
        }

        for(gy in 0 until gh) for(gx in 0 until gw){
            val start=gy*gw+gx
            if(visited[start])continue
            if(!blueAt(left+gx*step,top+gy*step)){visited[start]=true;continue}

            var head=0;var tail=0
            queue[tail++]=start;visited[start]=true
            var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var area=0

            while(head<tail){
                val p=queue[head++]
                val py=p/gw;val px=p%gw
                area++
                minGX=min(minGX,px);maxGX=max(maxGX,px)
                minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy
                    if(nx !in 0 until gw||ny !in 0 until gh)continue
                    val ni=ny*gw+nx
                    if(visited[ni])continue
                    visited[ni]=true
                    if(blueAt(left+nx*step,top+ny*step))queue[tail++]=ni
                }
            }

            if(area !in MIN_BADGE_AREA..MAX_BADGE_AREA)continue
            val box=BoundingBox(
                max(left,left+minGX*step-1),
                max(top,top+minGY*step-1),
                min(right,left+(maxGX+1)*step+1),
                min(bottom,top+(maxGY+1)*step+1)
            )
            if(box.width !in 18..50 || box.height !in 12..35)continue

            val density=(area.toFloat()*step*step/(box.width*box.height)).coerceIn(0f,1f)
            val aspectPenalty=abs(box.width/box.height.toFloat()-1.35f)
            val confidence=(62f+density*28f-max(0f,aspectPenalty-0.65f)*18f)
                .toInt().coerceIn(60,96)
            if(confidence>=68)found+=Badge(box,confidence)
        }

        val sorted=found.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
        val out=ArrayList<Badge>()
        for(b in sorted){
            if(out.none{abs(it.box.centerX-b.box.centerX)<18&&abs(it.box.centerY-b.box.centerY)<18})out+=b
        }
        return out
    }
}
