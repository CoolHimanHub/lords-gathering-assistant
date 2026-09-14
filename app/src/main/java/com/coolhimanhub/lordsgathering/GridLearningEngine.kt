package com.coolhimanhub.lordsgathering

import kotlin.math.abs
import kotlin.math.sqrt

/** Lightweight online grid learner.
 * Learns the isometric lattice from observed screen/game coordinate pairs.
 * No heavyweight model is required on every frame: robust incremental fitting
 * keeps the scanner responsive while retaining ML-friendly feature storage.
 */
class GridLearningEngine {
    data class Sample(val gx:Int,val gy:Int,val sx:Float,val sy:Float)
    data class Model(
        val ax:Float,val bx:Float,val cx:Float,
        val ay:Float,val by:Float,val cy:Float,
        val residual:Float,val samples:Int,val locked:Boolean
    )

    private val samples=ArrayList<Sample>()
    private var model:Model?=null

    @Synchronized fun reset(){samples.clear();model=null}

    @Synchronized fun add(sample:Sample):Model? {
        if(samples.size>120)samples.removeAt(0)
        samples.add(sample)
        if(samples.size<6)return model
        model=fitRobust()
        return model
    }

    @Synchronized fun current():Model?=model

    @Synchronized fun predict(gx:Int,gy:Int):Pair<Float,Float>? {
        val m=model?:return null
        return Pair(m.ax*gx+m.bx*gy+m.cx,m.ay*gx+m.by*gy+m.cy)
    }

    private fun fitRobust():Model? {
        val s=samples.toList()
        // Centered least-squares for two independent screen axes.
        val mx=s.map{it.gx.toDouble()}.average(); val my=s.map{it.gy.toDouble()}.average()
        val den=s.sumOf{((it.gx-mx)*(it.gx-mx)+(it.gy-my)*(it.gy-my)).toDouble()}
        if(den<1e-6)return null
        val sx=s.map{it.sx.toDouble()}.average(); val sy=s.map{it.sy.toDouble()}.average()
        val ax=s.sumOf{(it.gx-mx)*(it.sx-sx)}.toFloat()/s.sumOf{(it.gx-mx)*(it.gx-mx)}.toFloat().coerceAtLeast(1e-6f)
        val bx=s.sumOf{(it.gy-my)*(it.sx-sx)}.toFloat()/s.sumOf{(it.gy-my)*(it.gy-my)}.toFloat().coerceAtLeast(1e-6f)
        val ay=s.sumOf{(it.gx-mx)*(it.sy-sy)}.toFloat()/s.sumOf{(it.gx-mx)*(it.gx-mx)}.toFloat().coerceAtLeast(1e-6f)
        val by=s.sumOf{(it.gy-my)*(it.sy-sy)}.toFloat()/s.sumOf{(it.gy-my)*(it.gy-my)}.toFloat().coerceAtLeast(1e-6f)
        val cx=(sx-ax*mx-bx*my).toFloat(); val cy=(sy-ay*mx-by*my).toFloat()
        val residual=s.map{sqrt(((ax*it.gx+bx*it.gy+cx-it.sx)*(ax*it.gx+bx*it.gy+cx-it.sx)+(ay*it.gx+by*it.gy+cy-it.sy)*(ay*it.gx+by*it.gy+cy-it.sy)).toDouble()).toFloat()}.sorted()
        val med=residual[residual.size/2]
        return Model(ax,bx,cx,ay,by,cy,med,s.size,locked=s.size>=10 && med<=14f)
    }
}
