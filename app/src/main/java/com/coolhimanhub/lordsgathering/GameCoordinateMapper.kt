package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V42 robust isometric coordinate mapper.
 *
 * V41 used the current (possibly wrong) tile basis to predict where every RSS
 * should move before matching it. That creates a circular dependency: a bad
 * initial basis can prevent the correct RSS pairs from ever being accepted,
 * leaving calibration at S:0 forever.
 *
 * V42 breaks that loop. Between two genuinely different viewports it first
 * finds conservative one-to-one RSS matches from the observed screen motion,
 * then estimates the horizontal/vertical tile basis from the matched pairs.
 * Median estimates are used to reject accidental matches and only stable
 * observations update the learned basis.
 *
 * Physical taps remain screen-pixel based. Game X/Y is reporting/identity only.
 */
class GameCoordinateMapper {
    data class GameLocation(val x:Int,val y:Int)

    data class Calibration(
        val halfTileW:Float,
        val halfTileH:Float,
        val samples:Int,
        val ready:Boolean,
        val quality:Int
    )

    companion object {
        private const val REF_W=1536f
        private const val REF_H=707f
        private const val DEFAULT_HALF_W=32f
        private const val DEFAULT_HALF_H=16f
        private const val MIN_HALF_W=18f
        private const val MAX_HALF_W=60f
        private const val MIN_HALF_H=9f
        private const val MAX_HALF_H=30f

        // RSS art can move a little between frames because of animation.
        private const val PAIR_RADIUS=125f
        private const val MIN_MATCHES_FOR_UPDATE=1
        private const val MAX_MATCHES_PER_FRAME=40
        private const val STABILITY_RATIO=0.35f

        private fun scaleX(screenWidth:Int)=screenWidth/REF_W
        private fun scaleY(screenHeight:Int)=screenHeight/REF_H
    }

    private var halfTileW=DEFAULT_HALF_W
    private var halfTileH=DEFAULT_HALF_H
    private var samples=0
    private var previousViewport:MapViewportTracker.Viewport?=null
    private var previousPoints:List<Pair<Int,Int>> = emptyList()
    private var acceptedPairs=0
    private var rejectedPairs=0
    private var goodFrames=0
    private var badFrames=0

    @Synchronized
    fun observe(
        viewport:MapViewportTracker.Viewport,
        points:List<Pair<Int,Int>>,
        screenWidth:Int,
        screenHeight:Int
    ){
        val oldViewport=previousViewport
        val oldPoints=previousPoints

        if(oldViewport!=null && oldPoints.isNotEmpty() && points.isNotEmpty()){
            val dvx=viewport.x-oldViewport.x
            val dvy=viewport.y-oldViewport.y

            // Only learn from a real viewport change. Small header OCR jitter is
            // ignored, while normal map movement is accepted.
            if(abs(dvx)<=120 && abs(dvy)<=120 && (dvx!=0 || dvy!=0)){
                val sx=scaleX(screenWidth)
                val sy=scaleY(screenHeight)
                val matches=matchPoints(oldPoints,points)
                val wEstimates=ArrayList<Float>()
                val hEstimates=ArrayList<Float>()

                for(match in matches.take(MAX_MATCHES_PER_FRAME)){
                    val old=oldPoints[match.first]
                    val now=points[match.second]
                    val mdx=(now.first-old.first).toFloat()
                    val mdy=(now.second-old.second).toFloat()

                    val denomW=dvx-dvy
                    val denomH=dvx+dvy

                    if(denomW!=0){
                        val estimate=(-mdx/denomW)/sx
                        if(estimate in MIN_HALF_W..MAX_HALF_W)wEstimates+=estimate
                    }
                    if(denomH!=0){
                        val estimate=(-mdy/denomH)/sy
                        if(estimate in MIN_HALF_H..MAX_HALF_H)hEstimates+=estimate
                    }
                }

                val wMedian=median(wEstimates)
                val hMedian=median(hEstimates)
                val stableW=wMedian!=null && isStable(wEstimates,wMedian)
                val stableH=hMedian!=null && isStable(hEstimates,hMedian)

                if(matches.size>=MIN_MATCHES_FOR_UPDATE && (stableW||stableH)){
                    if(stableW){
                        halfTileW=blend(halfTileW,wMedian!!,0.30f)
                        samples++
                    }
                    if(stableH){
                        halfTileH=blend(halfTileH,hMedian!!,0.30f)
                        samples++
                    }
                    acceptedPairs+=matches.size
                    goodFrames++
                }else{
                    rejectedPairs+=matches.size.coerceAtLeast(1)
                    badFrames++
                }
            }
        }

        previousViewport=viewport
        previousPoints=points
    }

    /**
     * Match observations by actual screen displacement rather than by the
     * currently learned basis. This is the key V42 change: matching no longer
     * depends on calibration already being correct.
     */
    private fun matchPoints(
        oldPoints:List<Pair<Int,Int>>,
        newPoints:List<Pair<Int,Int>>
    ):List<Pair<Int,Int>>{
        val candidates=ArrayList<Triple<Float,Int,Int>>()
        for(i in oldPoints.indices){
            val old=oldPoints[i]
            for(j in newPoints.indices){
                val now=newPoints[j]
                val dx=(now.first-old.first).toFloat()
                val dy=(now.second-old.second).toFloat()
                val distance=(dx*dx+dy*dy).toDouble().let{Math.sqrt(it)}.toFloat()
                if(distance<=PAIR_RADIUS)candidates+=Triple(distance,i,j)
            }
        }

        // Greedy nearest-neighbour assignment is deterministic and prevents
        // one RSS from explaining multiple RSS observations.
        candidates.sortBy{it.first}
        val usedOld=HashSet<Int>()
        val usedNew=HashSet<Int>()
        val result=ArrayList<Pair<Int,Int>>()
        for((distance,i,j) in candidates){
            if(i in usedOld||j in usedNew)continue
            usedOld+=i;usedNew+=j;result+=i to j
        }
        return result
    }

    private fun median(values:List<Float>):Float?{
        if(values.isEmpty())return null
        val sorted=values.sorted()
        val mid=sorted.size/2
        return if(sorted.size%2==0)(sorted[mid-1]+sorted[mid])/2f else sorted[mid]
    }

    private fun isStable(values:List<Float>,centre:Float):Boolean{
        if(values.size<2)return true
        val maxDeviation=values.maxOf{abs(it-centre)}
        return maxDeviation<=max(centre*STABILITY_RATIO,2.5f)
    }

    private fun max(a:Float,b:Float)=if(a>b)a else b

    private fun blend(old:Float,new:Float,weight:Float):Float = old*(1f-weight)+new*weight

    @Synchronized
    fun calibration():Calibration {
        // A single clean cross-viewport pair can be enough to get a useful
        // estimate, but LOCKED requires repeated successful frames.
        val ready=goodFrames>=3 && samples>=4
        val sampleQuality=(samples*7).coerceAtMost(55)
        val frameQuality=(goodFrames*8).coerceAtMost(32)
        val rejectionPenalty=(badFrames.coerceAtMost(8)*2)
        val quality=(sampleQuality+frameQuality-rejectionPenalty).coerceIn(0,100)
        return Calibration(halfTileW,halfTileH,samples,ready,quality)
    }

    @Synchronized
    fun reset(){
        halfTileW=DEFAULT_HALF_W
        halfTileH=DEFAULT_HALF_H
        samples=0
        acceptedPairs=0
        rejectedPairs=0
        goodFrames=0
        badFrames=0
        previousViewport=null
        previousPoints=emptyList()
    }

    @Synchronized
    fun map(
        screenX:Int,
        screenY:Int,
        viewportX:Int,
        viewportY:Int,
        screenWidth:Int,
        screenHeight:Int
    ):GameLocation {
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
