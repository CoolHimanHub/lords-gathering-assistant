package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V40 adaptive isometric coordinate mapper.
 *
 * The mapper keeps the V38/V39 API but learns the pixel basis from repeated
 * viewport changes.  A single screenshot is never allowed to redefine the
 * grid: calibration requires a changed X/Y viewport and matching RSS badge
 * observations across two frames.  Physical taps remain screen-pixel based.
 */
class GameCoordinateMapper {
    data class GameLocation(val x:Int,val y:Int)

    data class Calibration(val halfTileW:Float,val halfTileH:Float,val samples:Int,val ready:Boolean)

    companion object {
        private const val REF_W=1536f
        private const val REF_H=707f
        private const val DEFAULT_HALF_W=32f
        private const val DEFAULT_HALF_H=16f
        private const val MIN_HALF_W=18f
        private const val MAX_HALF_W=60f
        private const val MIN_HALF_H=9f
        private const val MAX_HALF_H=30f
        private const val MATCH_RADIUS=120f

        private fun scaleX(screenWidth:Int)=screenWidth/REF_W
        private fun scaleY(screenHeight:Int)=screenHeight/REF_H
    }

    private var halfTileW=DEFAULT_HALF_W
    private var halfTileH=DEFAULT_HALF_H
    private var samples=0
    private var previousViewport:MapViewportTracker.Viewport?=null
    private var previousPoints:List<Pair<Int,Int>> = emptyList()

    @Synchronized
    fun observe(viewport:MapViewportTracker.Viewport, points:List<Pair<Int,Int>>, screenWidth:Int, screenHeight:Int){
        val oldViewport=previousViewport
        val oldPoints=previousPoints
        if(oldViewport!=null && oldPoints.isNotEmpty() && points.isNotEmpty()){
            val dvx=viewport.x-oldViewport.x
            val dvy=viewport.y-oldViewport.y
            if(abs(dvx)<=80 && abs(dvy)<=80 && (dvx!=0 || dvy!=0)){
                val sx=scaleX(screenWidth); val sy=scaleY(screenHeight)
                val expectedDx=-halfTileW*sx*(dvx-dvy)
                val expectedDy=-halfTileH*sy*(dvx+dvy)
                for(p in points){
                    val match=oldPoints.minByOrNull{q->
                        val dx=(p.first-q.first).toFloat()-expectedDx
                        val dy=(p.second-q.second).toFloat()-expectedDy
                        dx*dx+dy*dy
                    } ?: continue
                    val mdx=p.first-match.first
                    val mdy=p.second-match.second
                    val denomW=(dvx-dvy)
                    val denomH=(dvx+dvy)
                    if(denomW!=0){
                        val estimate=(-mdx.toFloat()/denomW)/sx
                        if(estimate in MIN_HALF_W..MAX_HALF_W && abs(mdx-expectedDx)<MATCH_RADIUS){
                            halfTileW=blend(halfTileW,estimate,0.18f); samples++
                        }
                    }
                    if(denomH!=0){
                        val estimate=(-mdy.toFloat()/denomH)/sy
                        if(estimate in MIN_HALF_H..MAX_HALF_H && abs(mdy-expectedDy)<MATCH_RADIUS){
                            halfTileH=blend(halfTileH,estimate,0.18f); samples++
                        }
                    }
                    if(samples>=60)break
                }
            }
        }
        previousViewport=viewport
        previousPoints=points
    }

    private fun blend(old:Float,new:Float,weight:Float):Float = old*(1f-weight)+new*weight

    @Synchronized
    fun calibration():Calibration = Calibration(
        halfTileW,halfTileH,samples,samples>=4
    )

    @Synchronized
    fun reset(){
        halfTileW=DEFAULT_HALF_W;halfTileH=DEFAULT_HALF_H;samples=0
        previousViewport=null;previousPoints= emptyList()
    }

    @Synchronized
    fun map(screenX:Int,screenY:Int,viewportX:Int,viewportY:Int,
            screenWidth:Int,screenHeight:Int):GameLocation {
        val sx=scaleX(screenWidth)
        val sy=scaleY(screenHeight)
        val centreX=screenWidth/2f
        val centreY=screenHeight/2f
        val hw=(halfTileW*sx).coerceAtLeast(1f)
        val hh=(halfTileH*sy).coerceAtLeast(1f)
        val dx=screenX-centreX
        val dy=screenY-centreY
        val worldDx=(dx/hw + dy/hh)/2f
        val worldDy=(dy/hh - dx/hw)/2f
        return GameLocation(
            (viewportX+worldDx).roundToInt().coerceIn(0,9999),
            (viewportY+worldDy).roundToInt().coerceIn(0,9999)
        )
    }
}
