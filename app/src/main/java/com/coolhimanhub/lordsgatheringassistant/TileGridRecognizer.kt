package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.abs

/**
 * V57.6: converts screen detections into stable grid cells and suppresses
 * duplicate detections from overlapping scan passes. It does not tap.
 */
class TileGridRecognizer(private val cellWidth:Int=96, private val cellHeight:Int=96) {
    data class Cell(val gx:Int,val gy:Int,val x:Int,val y:Int)

    fun cell(x:Int,y:Int):Cell {
        val gx=Math.round(x.toFloat()/cellWidth)
        val gy=Math.round(y.toFloat()/cellHeight)
        return Cell(gx,gy,gx*cellWidth,gy*cellHeight)
    }

    fun deduplicate(points:List<Pair<Int,Int>>):List<Cell> {
        val out=LinkedHashMap<Pair<Int,Int>,Cell>()
        for((x,y) in points){
            val c=cell(x,y)
            val key=c.gx to c.gy
            val previous=out[key]
            if(previous==null || abs(x-previous.x)+abs(y-previous.y) < cellWidth/2) out[key]=c
        }
        return out.values.toList()
    }
}
