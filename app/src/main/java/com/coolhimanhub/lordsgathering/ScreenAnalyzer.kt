package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** V57.9: multi-threshold RSS badge discovery + adaptive resource classification. */
class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int) { val width:Int get()=maxX-minX+1; val height:Int get()=maxY-minY+1; val centerX:Int get()=(minX+maxX)/2; val centerY:Int get()=(minY+maxY)/2 }
    data class RssDetection(val type:String,val level:Int,val centerX:Int,val centerY:Int,val boundingBox:BoundingBox,val confidence:Int,val occupied:Boolean,val dominantColor:Int,val moving:Boolean=false,val movingScore:Int=0)
    private data class Badge(val box:BoundingBox,val confidence:Int)
    private val viewportRecognizer:TextRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val badgeRecognizer:TextRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val resourceClassifier=ResourceTileClassifier(); private val gridPipeline=GridDetectionPipeline()
    companion object { private const val REF_W=1536f; private const val REF_H=707f; private const val REF_MAP_LEFT=410f; private const val REF_MAP_RIGHT=1420f; private const val REF_MAP_TOP=90f; private const val REF_BOTTOM_MARGIN=42f; private const val BLUE_MIN=48; private const val BLUE_DELTA_R=12; private const val BLUE_DELTA_G=2; private const val RED_MAX=195; private const val GLYPH_MIN_RGB=125; private const val GLYPH_MAX_SAT=165; private const val GLYPH_MIN_PIXELS=3; private const val LEVEL_OCR_TIMEOUT_MS=180L }
    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height,ignoredRegions:List<BoundingBox> = emptyList()):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400){ViewportOcrCache.clear();return emptyList()}
        try{ViewportOcrCache.set(ViewportOcrReader.read(bitmap,viewportRecognizer))}catch(_:Exception){ViewportOcrCache.clear()}
        val sx=bitmap.width/REF_W; val sy=bitmap.height/REF_H; val left=max((REF_MAP_LEFT*sx).toInt(),expectedRegionX.first); val right=min((REF_MAP_RIGHT*sx).toInt(),min(bitmap.width-1,expectedRegionX.last)); val top=max((REF_MAP_TOP*sy).toInt(),expectedRegionY.first); val bottom=min(bitmap.height-1-(REF_BOTTOM_MARGIN*sy).toInt(),expectedRegionY.last); if(right<=left||bottom<=top)return emptyList()
        val raw=findBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions).map{badge->
            val probeX=(badge.box.centerX-(18f*sx).toInt()).coerceIn(left+8,right-8); val probeY=(badge.box.centerY-(7f*sy).toInt()).coerceIn(top+8,bottom-8); val visual=resourceClassifier.classify(bitmap,probeX,probeY)
            val type=when(visual.type){ResourceTileClassifier.Type.FOOD->"food";ResourceTileClassifier.Type.TIMBER->"timber";ResourceTileClassifier.Type.STONE->"stone";ResourceTileClassifier.Type.ORE->"ore";ResourceTileClassifier.Type.GOLD->"gold";ResourceTileClassifier.Type.UNKNOWN->"unknown"}; val level=readBadgeLevel(bitmap,badge.box,sx,sy); val classifiedConfidence=if(visual.type==ResourceTileClassifier.Type.UNKNOWN)0 else min(badge.confidence,visual.confidence); val finalConfidence=when{level in 1..5&&classifiedConfidence>=45->min(95,classifiedConfidence+8);level in 1..5->min(90,badge.confidence);else->classifiedConfidence}; RssDetection(type,level,probeX,probeY,badge.box,finalConfidence,false,visual.redEvidence,false,0)
        }
        val result=gridPipeline.normalize(raw).map{it.detection}; val typeCounts=result.groupingBy{it.type}.eachCount(); val known=result.count{it.type!="unknown"}; val leveled=result.count{it.level in 1..5}; val candidateCount=result.count{it.confidence>=68&&it.type!="unknown"&&it.level in 1..5}; val unknownCount=result.count{it.type=="unknown"}; Log.i("LordsAssistantDiag","raw=${raw.size} grid=${result.size} known=$known leveled=$leveled candidates=$candidateCount types=$typeCounts unknown=$unknownCount"); result.forEachIndexed{index,d->Log.d("LordsAssistantDiag","tile#$index type=${d.type} level=${d.level} x=${d.centerX} y=${d.centerY} confidence=${d.confidence} occupied=${d.occupied} moving=${d.moving}")}; return result
    }
    private fun readBadgeLevel(bitmap:Bitmap,box:BoundingBox,sx:Float,sy:Float):Int{ val padX=max(3,(5f*sx).toInt()); val padY=max(3,(5f*sy).toInt()); val l=(box.minX-padX).coerceAtLeast(0); val t=(box.minY-padY).coerceAtLeast(0); val r=(box.maxX+padX).coerceAtMost(bitmap.width-1); val b=(box.maxY+padY).coerceAtMost(bitmap.height-1); if(r<=l||b<=t)return 0; return try{val crop=Bitmap.createBitmap(bitmap,l,t,r-l+1,b-t+1); val scaled=Bitmap.createScaledBitmap(crop,max(48,crop.width*4),max(48,crop.height*4),true); val text=try{Tasks.await(badgeRecognizer.process(InputImage.fromBitmap(scaled,0)),LEVEL_OCR_TIMEOUT_MS,TimeUnit.MILLISECONDS)?.text?:""}catch(_:Exception){""}; try{scaled.recycle()}catch(_:Exception){}; try{crop.recycle()}catch(_:Exception){}; Regex("[1-5]").find(text)?.value?.toIntOrNull()?.coerceIn(1,5)?:0}catch(_:Exception){0} }
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int,sx:Float,sy:Float,ignoredRegions:List<BoundingBox>):List<Badge>{ val strict=collectBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions,false); val relaxed=collectBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions,true); val all=(strict+relaxed).sortedWith(compareByDescending<Badge>{it.confidence}.thenBy{it.box.centerY}.thenBy{it.box.centerX}); val out=ArrayList<Badge>(); val mergeX=max(16,(16f*sx).toInt()); val mergeY=max(15,(15f*sy).toInt()); for(b in all)if(out.none{e->abs(e.box.centerX-b.box.centerX)<mergeX&&abs(e.box.centerY-b.box.centerY)<mergeY})out+=b; return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX})) }
    private fun collectBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int,sx:Float,sy:Float,ignoredRegions:List<BoundingBox>,relaxed:Boolean):List<Badge>{
        val width=right-left+1; val height=bottom-top+1; val size=width*height; val pixels=IntArray(size); bitmap.getPixels(pixels,0,width,left,top); fun ignored(x:Int,y:Int)=ignoredRegions.any{region->x in region.minX..region.maxX&&y in region.minY..region.maxY}; val blue=BooleanArray(size); var i=0
        while(i<size){val lx=i%width; val ly=i/width; val ax=left+lx; val ay=top+ly; if(!ignored(ax,ay)){val c=pixels[i]; val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c); val minBlue=if(relaxed)40 else BLUE_MIN; val deltaR=if(relaxed)8 else BLUE_DELTA_R; val deltaG=if(relaxed)-2 else BLUE_DELTA_G; blue[i]=b>=minBlue&&b-r>=deltaR&&b-g>=deltaG&&r<=if(relaxed)215 else RED_MAX}; i++}
        val visited=BooleanArray(size); val queue=IntArray(size); val found=ArrayList<Badge>(); val minW=max(7,(10f*sx).toInt()); val maxW=max(minW+2,(60f*sx).toInt()); val minH=max(6,(9f*sy).toInt()); val maxH=max(minH+2,(46f*sy).toInt()); val minArea=max(30,(55f*sx*sy).toInt()); val maxArea=max(minArea+1,(1500f*sx*sy).toInt())
        for(y in 0 until height)for(x in 0 until width){val start=y*width+x; if(visited[start]||!blue[start])continue; var head=0; var tail=0; queue[tail++]=start; visited[start]=true; var minX=x; var maxX=x; var minY=y; var maxY=y; var area=0
            while(head<tail){val p=queue[head++]; val py=p/width; val px=p%width; minX=min(minX,px); maxX=max(maxX,px); minY=min(minY,py); maxY=max(maxY,py); area++; for(dy in -1..1)for(dx in -1..1)if(dx!=0||dy!=0){val nx=px+dx; val ny=py+dy; if(nx !in 0 until width||ny !in 0 until height)continue; val ni=ny*width+nx; if(!visited[ni]&&blue[ni]){visited[ni]=true; queue[tail++]=ni}}}
            val bw=maxX-minX+1; val bh=maxY-minY+1; if(bw !in minW..maxW||bh !in minH..maxH||area !in minArea..maxArea)continue; val density=area.toFloat()/(bw*bh).toFloat(); if(density<if(relaxed).07f else .10f||density>.97f)continue; var light=0; for(gy in minY..maxY)for(gx in minX..maxX){val c=pixels[gy*width+gx]; val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c); val mx=max(r,max(g,b)); val mn=min(r,min(g,b)); if(r>=GLYPH_MIN_RGB&&g>=GLYPH_MIN_RGB&&b>=GLYPH_MIN_RGB&&mx-mn<=GLYPH_MAX_SAT)light++}; if(light<if(relaxed)2 else GLYPH_MIN_PIXELS)continue
            val ax1=left+minX; val ay1=top+minY; val ax2=left+maxX; val ay2=top+maxY; if(ignoredRegions.any{region->ax1<=region.maxX&&ax2>=region.minX&&ay1<=region.maxY&&ay2>=region.minY})continue; val aspect=bw.toFloat()/bh; val aspectScore=(1f-abs(aspect-1.35f)/1.9f).coerceIn(0f,1f); val densityScore=(1f-abs(density-.45f)/.60f).coerceIn(0f,1f); val glyphScore=light.coerceAtMost(80)/80f; val base=if(relaxed)58f else 64f; val confidence=(base+16f*aspectScore+12f*densityScore+8f*glyphScore).toInt().coerceIn(if(relaxed)58 else 64,96); found+=Badge(BoundingBox(ax1,ay1,ax2,ay2),confidence)
        }; return found
    }
    fun close(){try{viewportRecognizer.close()}catch(_:Exception){};try{badgeRecognizer.close()}catch(_:Exception){};ViewportOcrCache.clear()}
}
