package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** V57: lightweight visual classifier for resource tiles.
 *
 * It deliberately does not claim a type from a single pixel. It samples the
 * resource-art area around each validated badge, suppresses UI/blue badge
 * pixels, and returns UNKNOWN when evidence is weak. This keeps the runtime
 * cheap and prevents false gathers.
 */
class ResourceTileClassifier {
    enum class Type { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    data class Result(val type:Type,val confidence:Int,val redEvidence:Int)

    fun classify(bitmap:Bitmap,cx:Int,cy:Int):Result {
        val radiusX=(42f*bitmap.width/1536f).toInt().coerceAtLeast(18)
        val radiusY=(34f*bitmap.height/707f).toInt().coerceAtLeast(14)
        var n=0; var r=0;var g=0;var b=0;var warm=0;var green=0;var gray=0;var blue=0
        val left=max(0,cx-radiusX);val right=min(bitmap.width-1,cx+radiusX)
        val top=max(0,cy-radiusY);val bottom=min(bitmap.height-1,cy+radiusY)
        for(y in top..bottom) for(x in left..right){
            val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c)
            // Ignore near-white UI and the saturated blue level badge.
            if(rr>220&&gg>220&&bb>220)continue
            val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb))
            if(mx-mn<18){ if(mx in 55..205)gray++; continue }
            if(bb>rr+22&&bb>gg+10){blue++;continue}
            n++
            if(rr>gg*1.18f&&rr>bb*1.20f)warm++
            if(gg>rr*1.10f&&gg>bb*1.10f)green++
            if(rr>125&&gg>105&&bb<95)warm++
            r+=rr;g+=gg;b+=bb
            if(rr>145&&gg<100&&bb<100)r++
        }
        if(n<25)return Result(Type.UNKNOWN,0,r)
        val total=(warm+green+gray+blue).coerceAtLeast(1)
        val candidates=listOf(
            Type.FOOD to warm,
            Type.TIMBER to green,
            Type.STONE to gray,
            Type.ORE to blue,
            Type.GOLD to warm/2
        ).sortedByDescending{it.second}
        val best=candidates.first()
        val conf=(best.second*100/total).coerceIn(0,100)
        return if(conf<48)Result(Type.UNKNOWN,conf,r) else Result(best.first,conf,r)
    }
}
