package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * V57.5: resource-art classifier.
 *
 * The level badge is used only as a locator by ScreenAnalyzer. This classifier
 * samples the artwork immediately up/left of that badge and deliberately
 * ignores the blue badge, red UI markers and pale map background.
 *
 * Classification is conservative: weak or overlapping evidence becomes
 * UNKNOWN and therefore cannot become an auto-gather target.
 */
class ResourceTileClassifier {
    enum class Type { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    data class Result(val type:Type,val confidence:Int,val redEvidence:Int)

    fun classify(bitmap:Bitmap,cx:Int,cy:Int):Result {
        val sx=bitmap.width/1536f
        val sy=bitmap.height/707f

        // cx/cy is the level-badge centre. The resource artwork is normally
        // above-left of it. Keep the window tight so map grass does not drown
        // the icon's colour signature.
        val left=max(0,cx-(42f*sx).toInt())
        val right=min(bitmap.width-1,cx-(5f*sx).toInt())
        val top=max(0,cy-(34f*sy).toInt())
        val bottom=min(bitmap.height-1,cy+(9f*sy).toInt())

        var samples=0
        var warm=0
        var brown=0
        var neutral=0
        var blue=0
        var cyan=0
        var purple=0
        var vivid=0
        var green=0
        var redEvidence=0

        for(y in top..bottom) for(x in left..right){
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            val mx=max(r,max(g,b)); val mn=min(r,min(g,b)); val spread=mx-mn

            // Exclude pale UI/map pixels and the blue level badge.
            if(mx>220 && mn>195) continue
            if(b>r+25 && b>g+12 && b>120) continue
            if(r<35 && g<35 && b<35) continue

            samples++
            if(r>150 && g<110 && b<110) redEvidence++

            val hsv=FloatArray(3)
            Color.RGBToHSV(r,g,b,hsv)
            val h=hsv[0]; val s=hsv[1]; val v=hsv[2]

            if(s>0.28f && v>0.25f)vivid++
            if((h<45f || h>=330f) && s>0.25f && v>0.28f)warm++
            if(h in 15f..45f && s>0.22f && v in 0.20f..0.80f)brown++
            if((h in 45f..75f) && s>0.25f && v>0.35f)green++
            if(h in 45f..68f && s>0.30f && v>0.45f)warm++
            if(h in 175f..250f && s>0.22f && v>0.25f)blue++
            if(h in 175f..205f && s>0.30f && v>0.35f)cyan++
            if(h in 250f..330f && s>0.20f && v>0.22f)purple++
            if(s<0.25f && v in 0.25f..0.82f)neutral++
        }

        if(samples<35)return Result(Type.UNKNOWN,0,redEvidence)

        // Lords Mobile map artwork has useful visual families:
        // food/resource piles are multicolour/saturated, timber is warm brown,
        // stone is muted neutral/purple, ore is blue/cyan, gold is yellow.
        val food=vivid + purple + redEvidence
        val timber=brown*2 + warm/2
        val stone=neutral*2 + purple/2
        val ore=blue*2 + cyan*2
        val gold=(warm*2) + yellowScore(bitmap,left,right,top,bottom)

        val scores=linkedMapOf(
            Type.FOOD to food,
            Type.TIMBER to timber,
            Type.STONE to stone,
            Type.ORE to ore,
            Type.GOLD to gold
        )

        val ranked=scores.entries.sortedByDescending{it.value}
        val best=ranked.first()
        val second=ranked.getOrNull(1)?.value ?: 0
        val total=ranked.sumOf{it.value}.coerceAtLeast(1)
        val share=best.value.toFloat()/total
        val margin=(best.value-second).toFloat()/total
        val confidence=(100f*(0.62f*share+0.38f*margin)).toInt().coerceIn(0,100)

        return if(best.value<=0 || share<0.28f || margin<0.06f || confidence<38){
            Result(Type.UNKNOWN,confidence,redEvidence)
        }else{
            Result(best.key,confidence,redEvidence)
        }
    }

    private fun yellowScore(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):Int{
        var score=0
        for(y in top..bottom)for(x in left..right){
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            if(r>145 && g>105 && b<85 && r>g*0.92f)score++
        }
        return score
    }
}
