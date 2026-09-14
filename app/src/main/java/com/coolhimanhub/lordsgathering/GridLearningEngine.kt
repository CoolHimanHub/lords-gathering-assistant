package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.sqrt

/** Lightweight online isometric-grid learner used by the live scanner. */
class GridLearningEngine {
    data class Sample(val gx:Int,val gy:Int,val sx:Float,val sy:Float)
    data class Model(val ax:Float,val bx:Float,val cx:Float,val ay:Float,val by:Float,val cy:Float,val residual:Float,val samples:Int,val locked:Boolean)
    private val samples=ArrayList<Sample>()
    private var model:Model?=null
    @Synchronized fun reset(){samples.clear();model=null}
    @Synchronized fun add(s:Sample):Model? { if(samples.size>=120)samples.removeAt(0);samples.add(s);if(samples.size>=6)model=fit();return model }
    @Synchronized fun current():Model?=model
    @Synchronized fun predict(gx:Int,gy:Int):Pair<Float,Float>? { val m=model?:return null;return Pair(m.ax*gx+m.bx*gy+m.cx,m.ay*gx+m.by*gy+m.cy) }
    private fun fit():Model? {
        val s=samples.toList(); val mx=s.map{it.gx.toDouble()}.average();val my=s.map{it.gy.toDouble()}.average();val sx=s.map{it.sx.toDouble()}.average();val sy=s.map{it.sy.toDouble()}.average()
        val xx=s.sumOf{(it.gx-mx)*(it.gx-mx)}.coerceAtLeast(1e-6);val yy=s.sumOf{(it.gy-my)*(it.gy-my)}.coerceAtLeast(1e-6)
        val ax=(s.sumOf{(it.gx-mx)*(it.sx-sx)}/xx).toFloat();val bx=(s.sumOf{(it.gy-my)*(it.sx-sx)}/yy).toFloat();val ay=(s.sumOf{(it.gx-mx)*(it.sy-sy)}/xx).toFloat();val by=(s.sumOf{(it.gy-my)*(it.sy-sy)}/yy).toFloat()
        val cx=(sx-ax*mx-bx*my).toFloat();val cy=(sy-ay*mx-by*my).toFloat()
        val errors=s.map{sqrt(((ax*it.gx+bx*it.gy+cx-it.sx)*(ax*it.gx+bx*it.gy+cx-it.sx)+(ay*it.gx+by*it.gy+cy-it.sy)*(ay*it.gx+by*it.gy+cy-it.sy)).toDouble()).toFloat()}.sorted()
        val med=errors[errors.size/2]
        return Model(ax,bx,cx,ay,by,cy,med,s.size,s.size>=10&&med<=14f)
    }
}
