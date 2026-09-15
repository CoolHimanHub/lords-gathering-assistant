package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.roundToInt

/** V57.6: stable screen-grid normalization; never performs gestures. */
class TileGridEngine {
    data class GridTile(val column:Int,val row:Int,val detection:ScreenAnalyzer.RssDetection)

    fun normalize(detections:List<ScreenAnalyzer.RssDetection>, cellWidth:Int=72, cellHeight:Int=54):List<GridTile> {
        if(detections.isEmpty()) return emptyList()
        val w=cellWidth.coerceAtLeast(24)
        val h=cellHeight.coerceAtLeast(24)
        val best=LinkedHashMap<Pair<Int,Int>,ScreenAnalyzer.RssDetection>()
        detections.forEach { d ->
            val key=(d.centerX.toFloat()/w).roundToInt() to (d.centerY.toFloat()/h).roundToInt()
            val old=best[key]
            if(old==null || score(d)>score(old)) best[key]=d
        }
        return best.entries.sortedWith(compareBy({it.key.second},{it.key.first})).map {
            GridTile(it.key.first,it.key.second,it.value)
        }
    }

    private fun score(d:ScreenAnalyzer.RssDetection):Int =
        d.confidence + if(d.type!="unknown") 12 else 0 + if(d.level in 1..5) 10 else 0
}
