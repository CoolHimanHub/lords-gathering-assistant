package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * V37 fast focused map-coordinate OCR.
 *
 * Live recordings show the coordinate HUD around the upper-middle map area.
 * V37 narrows the crop, tries the normal image first, and only creates a
 * grayscale retry when necessary. This replaces the old 4-crop x 3-variant
 * worst-case path and avoids spending most of a scan on OCR.
 */
object ViewportOcrReader {
    data class Result(val x:Int,val y:Int)

    private const val MIN_COORD=0
    private const val MAX_COORD=9999
    private const val OCR_TIMEOUT_MS=550L

    fun read(bitmap:Bitmap,recognizer:TextRecognizer):Result?{
        val w=bitmap.width; val h=bitmap.height
        if(w<600 || h<400) return null

        // Tight crop calibrated from the 2756x1268 live recordings. Normalized
        // coordinates keep it compatible with the earlier 1536x707 captures.
        val crops=listOf(
            Crop((w*0.47f).toInt(),(h*0.095f).toInt(),(w*0.64f).toInt(),(h*0.205f).toInt()),
            Crop((w*0.43f).toInt(),(h*0.065f).toInt(),(w*0.67f).toInt(),(h*0.225f).toInt())
        )

        for(definition in crops){
            val crop=makeCrop(bitmap,definition) ?: continue
            try{
                // Fast path: original crop.
                readOnce(crop,recognizer)?.let{return it}
                // Slow path only after the normal image fails.
                val gray=makeGray(crop)
                try{ readOnce(gray,recognizer)?.let{return it} }
                finally{ try{gray.recycle()}catch(_:Exception){} }
            }finally{ try{crop.recycle()}catch(_:Exception){} }
        }
        return null
    }

    private fun readOnce(source:Bitmap,recognizer:TextRecognizer):Result?{
        val scaled=try{
            Bitmap.createScaledBitmap(source,max(source.width*3,1),max(source.height*3,1),true)
        }catch(_:Exception){return null}
        return try{
            val result=try{
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(scaled,0)),
                    OCR_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }catch(_:Exception){null}
            if(result==null) null
            else parse(result.text) ?: parse(repair(result.text))
        }finally{try{scaled.recycle()}catch(_:Exception){}}
    }

    private data class Crop(val left:Int,val top:Int,val right:Int,val bottom:Int)

    private fun makeCrop(bitmap:Bitmap,c:Crop):Bitmap?{
        val left=c.left.coerceIn(0,bitmap.width-1)
        val top=c.top.coerceIn(0,bitmap.height-1)
        val right=c.right.coerceIn(left+1,bitmap.width)
        val bottom=c.bottom.coerceIn(top+1,bitmap.height)
        if(right-left<30 || bottom-top<12)return null
        return try{Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top)}catch(_:Exception){null}
    }

    private fun makeGray(source:Bitmap):Bitmap{
        val gray=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})
        Canvas(gray).drawBitmap(source,null,Rect(0,0,source.width,source.height),paint)
        return gray
    }

    private fun parse(text:String):Result?{
        val compact=normalize(text)
        val patterns=listOf(
            Regex("\\bX\\s*[:=]\\s*(\\d{1,4})\\s*[^0-9A-Z]{1,10}\\s*Y\\s*[:=]\\s*(\\d{1,4})\\b",RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*(\\d{1,4})\\s*[^0-9A-Z]{1,10}\\s*Y\\s*(\\d{1,4})\\b",RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*[:=]?\\s*(\\d{1,4})\\D{1,12}Y\\s*[:=]?\\s*(\\d{1,4})\\b",RegexOption.IGNORE_CASE)
        )
        for(pattern in patterns){
            val m=pattern.find(compact) ?: continue
            val x=m.groupValues[1].toIntOrNull() ?: continue
            val y=m.groupValues[2].toIntOrNull() ?: continue
            if(x in MIN_COORD..MAX_COORD && y in MIN_COORD..MAX_COORD)return Result(x,y)
        }
        return null
    }

    private fun normalize(text:String):String=text
        .replace('\n',' ').replace('\r',' ')
        .replace('|','I').replace('—','-').replace('–','-')
        .replace('：',':').replace('=',':')
        .replace(Regex("(?i)\\bK\\s*[:;.]"),"X:")
        .replace(Regex("(?i)\\bX\\s*[;.]"),"X:")
        .replace(Regex("(?i)\\bY\\s*[;.]"),"Y:")
        .replace(Regex("\\s+")," ").trim()

    private fun repair(text:String):String=normalize(text)
        .replace(Regex("(?i)\\bX\\s*[:;.]?\\s*(?=\\d)"),"X:")
        .replace(Regex("(?i)\\bY\\s*[:;.]?\\s*(?=\\d)"),"Y:")
}
