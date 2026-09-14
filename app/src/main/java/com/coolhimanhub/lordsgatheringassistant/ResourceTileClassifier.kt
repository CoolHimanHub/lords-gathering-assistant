package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Lightweight visual resource classifier.
 *
 * The map badge tells us WHERE a candidate is; this classifier estimates WHAT
 * is underneath it. It intentionally returns UNKNOWN when evidence overlaps,
 * because an uncertain resource must never be auto-gathered.
 */
class ResourceTileClassifier {
    enum class Type { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    data class Result(val type:Type,val confidence:Int,val redEvidence:Int)

    fun classify(bitmap:Bitmap,cx:Int,cy:Int):Result {
        val sx=bitmap.width/1536f
        val sy=bitmap.height/707f
        val rx=max(22,(48f*sx).toInt())
        val ry=max(16,(38f*sy).toInt())
        val left=max(0,cx-rx); val right=min(bitmap.width-1,cx+rx)
        val top=max(0,cy-ry); val bottom=min(bitmap.height-1,cy+ry)

        var samples=0
        var green=0; var darkGreen=0; var yellow=0; var gray=0; var blueGray=0; var brown=0
        var redEvidence=0

        for(y in top..bottom) for(x in left..right){
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            val mx=max(r,max(g,b)); val mn=min(r,min(g,b)); val spread=mx-mn

            // UI/background pixels and the blue level badge are poor evidence.
            if(mx>225 && mn>205) continue
            if(b>r+28 && b>g+12) continue
            if(spread<12 && mx>205) continue

            samples++
            if(r>150 && g<105 && b<105) redEvidence++

            if(g>r*1.10f && g>b*1.08f){
                green++
                if(g<145) darkGreen++
            }
            if(r>145 && g>120 && b<105 && r>g*0.92f) yellow++
            if(spread<35 && mx in 55..205) gray++
            if(b>=r-8 && b>=g-8 && mx in 55..185) blueGray++
            if(r>85 && g>50 && g<r*0.88f && b<75) brown++
        }

        if(samples<30)return Result(Type.UNKNOWN,0,redEvidence)

        // Distinguish gold from food by yellow/high-red warmth rather than
        // treating every warm pixel as FOOD.
        val scores=linkedMapOf(
            Type.GOLD to (yellow*2 + brown/2),
            Type.FOOD to (green + yellow/3),
            Type.TIMBER to (darkGreen*2 + brown),
            Type.STONE to gray,
            Type.ORE to blueGray
        )

        val ranked=scores.entries.sortedByDescending{it.value}
        val best=ranked.first()
        val second=ranked.getOrNull(1)?.value ?: 0
        val total=ranked.sumOf{it.value}.coerceAtLeast(1)
        val share=best.value.toFloat()/total
        val margin=(best.value-second).toFloat()/total
        val confidence=(100f*(0.65f*share+0.35f*margin)).toInt().coerceIn(0,100)

        // Require both dominance and separation. Borderline warm/green tiles
        // are deliberately UNKNOWN rather than guessed as gold/food.
        return if(best.value<=0 || share<0.30f || margin<0.08f || confidence<42){
            Result(Type.UNKNOWN,confidence,redEvidence)
        }else{
            Result(best.key,confidence,redEvidence)
        }
    }
}
