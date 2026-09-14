package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** V57: resolution-aware RSS badge detection plus resource classification. */
class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int) {
        val width:Int get()=maxX-minX+1
        val height:Int get()=maxY-minY+1
        val centerX:Int get()=(minX+maxX)/2
        val centerY:Int get()=(minY+maxY)/2
    }
    data class RssDetection(
        val type:String,val level:Int,val centerX:Int,val centerY:Int,
        val boundingBox:BoundingBox,val confidence:Int,val occupied:Boolean,
        val dominantColor:Int,val moving:Boolean=false,val movingScore:Int=0
    )
    private data class Badge(val box:BoundingBox,val confidence:Int)
    private val viewportRecognizer:TextRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val resourceClassifier=ResourceTileClassifier()
    companion object {
        private const val REF_W=1536f;private const val REF_H=707f
        private const val REF_MAP_LEFT=410f;private const val REF_MAP_RIGHT=1420f
        private const val REF_MAP_TOP=90f;private const val REF_BOTTOM_MARGIN=42f
        private const val BLUE_MIN=85;private const val BLUE_DELTA_R=25;private const val BLUE_DELTA_G=8;private const val RED_MAX=145
        private const val GLYPH_MIN_RGB=150;private const val GLYPH_MAX_SAT=125;private const val GLYPH_MIN_PIXELS=8
    }
    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height,ignoredRegions:List<BoundingBox> = emptyList()):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400){ViewportOcrCache.clear();return emptyList()}
        try{ViewportOcrCache.set(ViewportOcrReader.read(bitmap,viewportRecognizer))}catch(_:Exception){ViewportOcrCache.clear()}
        val sx=bitmap.width/REF_W;val sy=bitmap.height/REF_H
        val left=max((REF_MAP_LEFT*sx).toInt(),expectedRegionX.first);val right=min((REF_MAP_RIGHT*sx).toInt(),min(bitmap.width-1,expectedRegionX.last))
        val top=max((REF_MAP_TOP*sy).toInt(),expectedRegionY.first);val bottom=min(bitmap.height-1-(REF_BOTTOM_MARGIN*sy).toInt(),expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()
        return findBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions).map{badge->
            val probeX=(badge.box.centerX-(18f*sx).toInt()).coerceIn(left+8,right-8)
            val probeY=(badge.box.centerY-(7f*sy).toInt()).coerceIn(top+8,bottom-8)
            val visual=resourceClassifier.classify(bitmap,probeX,probeY)
            val type=when(visual.type){
                ResourceTileClassifier.Type.FOOD->"food"
                ResourceTileClassifier.Type.TIMBER->"timber"
                ResourceTileClassifier.Type.STONE->"stone"
                ResourceTileClassifier.Type.ORE->"ore"
                ResourceTileClassifier.Type.GOLD->"gold"
                ResourceTileClassifier.Type.UNKNOWN->"unknown"
            }
            RssDetection(type=type,level=0,centerX=probeX,centerY=probeY,boundingBox=badge.box,
                confidence=if(visual.type==ResourceTileClassifier.Type.UNKNOWN)0 else min(badge.confidence,visual.confidence),
                occupied=false,dominantColor=visual.redEvidence)
        }
    }
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int,sx:Float,sy:Float,ignoredRegions:List<BoundingBox>):List<Badge>{
        val width=right-left+1;val height=bottom-top+1;val size=width*height;val pixels=IntArray(size)
        bitmap.getPixels(pixels,0,width,left,top,width,height)
        fun ignored(x:Int,y:Int)=ignoredRegions.any{x in it.minX..it.maxX&&y in it.minY..it.maxY}
        val blue=BooleanArray(size);var i=0
        while(i<size){val lx=i%width;val ly=i/width;val ax=left+lx;val ay=top+ly
            if(!ignored(ax,ay)){val c=pixels[i];val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);blue[i]=b>=BLUE_MIN&&b-r>=BLUE_DELTA_R&&b-g>=BLUE_DELTA_G&&r<=RED_MAX};i++}
        val visited=BooleanArray(size);val queue=IntArray(size);val found=ArrayList<Badge>()
        val minW=max(12,(18f*sx).toInt());val maxW=max(minW+1,(45f*sx).toInt());val minH=max(10,(16f*sy).toInt());val maxH=max(minH+1,(35f*sy).toInt())
        val minArea=max(80,(160f*sx*sy).toInt());val maxArea=max(minArea+1,(700f*sx*sy).toInt())
        for(y in 0 until height)for(x in 0 until width){val start=y*width+x;if(visited[start]||!blue[start])continue
            var head=0;var tail=0;queue[tail++]=start;visited[start]=true;var minX=x;var maxX=x;var minY=y;var maxY=y;var area=0
            while(head<tail){val p=queue[head++];val py=p/width;val px=p%width;minX=min(minX,px);maxX=max(maxX,px);minY=min(minY,py);maxY=max(maxY,py);area++
                for(dy in -1..1)for(dx in -1..1)if(dx!=0||dy!=0){val nx=px+dx;val ny=py+dy;if(nx !in 0 until width||ny !in 0 until height)continue;val ni=ny*width+nx;if(!visited[ni]&&blue[ni]){visited[ni]=true;queue[tail++]=ni}}
            }
            val bw=maxX-minX+1;val bh=maxY-minY+1;if(bw !in minW..maxW||bh !in minH..maxH||area !in minArea..maxArea)continue
            val density=area.toFloat()/(bw*bh).toFloat();if(density<.30f||density>.82f)continue
            var light=0;for(gy in minY..maxY)for(gx in minX..maxX){val c=pixels[gy*width+gx];val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);val mx=maxOf(r,g,b);val mn=minOf(r,g,b);if(r>=GLYPH_MIN_RGB&&g>=GLYPH_MIN_RGB&&b>=GLYPH_MIN_RGB&&mx-mn<=GLYPH_MAX_SAT)light++}
            if(light<GLYPH_MIN_PIXELS)continue
            val ax1=left+minX;val ay1=top+minY;val ax2=left+maxX;val ay2=top+maxY
            if(ignoredRegions.any{ax1<=it.maxX&&ax2>=it.minX&&ay1<=it.maxY&&ay2>=it.minY})continue
            val aspect=bw.toFloat()/bh;val aspectScore=(1f-abs(aspect-1.35f)/1.55f).coerceIn(0f,1f);val densityScore=(1f-abs(density-.52f)/.52f).coerceIn(0f,1f);val glyphScore=(light.coerceAtMost(80)/80f)
            val confidence=(70f+10f*aspectScore+9f*densityScore+6f*glyphScore).toInt().coerceIn(70,95)
            found+=Badge(BoundingBox(ax1,ay1,ax2,ay2),confidence)
        }
        val sorted=found.sortedWith(compareByDescending<Badge>{it.confidence}.thenBy{it.box.centerY}.thenBy{it.box.centerX});val out=ArrayList<Badge>();val mergeX=max(24,(18f*sx).toInt());val mergeY=max(20,(18f*sy).toInt())
        for(b in sorted)if(out.none{abs(it.box.centerX-b.box.centerX)<mergeX&&abs(it.box.centerY-b.box.centerY)<mergeY})out+=b
        return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))
    }
    fun close(){try{viewportRecognizer.close()}catch(_:Exception){};ViewportOcrCache.clear()}
}
