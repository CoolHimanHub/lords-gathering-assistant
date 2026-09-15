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

/** V58: independent candidate discovery + RSS badge confirmation. */
class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int){val width:Int get()=maxX-minX+1;val height:Int get()=maxY-minY+1;val centerX:Int get()=(minX+maxX)/2;val centerY:Int get()=(minY+maxY)/2}
    data class RssDetection(val type:String,val level:Int,val centerX:Int,val centerY:Int,val boundingBox:BoundingBox,val confidence:Int,val occupied:Boolean,val dominantColor:Int,val moving:Boolean=false,val movingScore:Int=0)
    private data class Badge(val box:BoundingBox,val confidence:Int)
    private val viewportRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val badgeRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val resourceClassifier=ResourceTileClassifier()
    private val gridPipeline=GridDetectionPipeline()
    private val candidateScanner=TileCandidateScanner()
    companion object{private const val REF_W=1536f;private const val REF_H=707f;private const val REF_MAP_LEFT=410f;private const val REF_MAP_RIGHT=1420f;private const val REF_MAP_TOP=90f;private const val REF_BOTTOM_MARGIN=42f;private const val BLUE_MIN=48;private const val BLUE_DELTA_R=12;private const val BLUE_DELTA_G=2;private const val RED_MAX=195;private const val LEVEL_OCR_TIMEOUT_MS=180L}
    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height,ignoredRegions:List<BoundingBox> = emptyList()):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400){ViewportOcrCache.clear();return emptyList()}
        try{ViewportOcrCache.set(ViewportOcrReader.read(bitmap,viewportRecognizer))}catch(_:Exception){ViewportOcrCache.clear()}
        val sx=bitmap.width/REF_W;val sy=bitmap.height/REF_H
        val left=max((REF_MAP_LEFT*sx).toInt(),expectedRegionX.first);val right=min((REF_MAP_RIGHT*sx).toInt(),min(bitmap.width-1,expectedRegionX.last));val top=max((REF_MAP_TOP*sy).toInt(),expectedRegionY.first);val bottom=min(bitmap.height-1-(REF_BOTTOM_MARGIN*sy).toInt(),expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()
        val raw=findBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions).map{b->classifyBadge(bitmap,b,sx,sy)}.toMutableList()
        val candidates=candidateScanner.scan(bitmap);var added=0
        for(c in candidates){if(raw.any{abs(it.centerX-c.x)<24*sx&&abs(it.centerY-c.y)<24*sy})continue;val visual=resourceClassifier.classify(bitmap,c.x,c.y);if(visual.type==ResourceTileClassifier.Type.UNKNOWN||visual.confidence<38)continue;val box=BoundingBox((c.x-20*sx).toInt().coerceAtLeast(0),(c.y-20*sy).toInt().coerceAtLeast(0),(c.x+20*sx).toInt().coerceAtMost(bitmap.width-1),(c.y+20*sy).toInt().coerceAtMost(bitmap.height-1));val type=when(visual.type){ResourceTileClassifier.Type.FOOD->"food";ResourceTileClassifier.Type.TIMBER->"timber";ResourceTileClassifier.Type.STONE->"stone";ResourceTileClassifier.Type.ORE->"ore";ResourceTileClassifier.Type.GOLD->"gold";else->"unknown"};raw+=RssDetection(type,0,c.x,c.y,box,min(72,visual.confidence),false,visual.redEvidence);added++}
        val result=gridPipeline.normalize(raw).map{it.detection};val typeCounts=result.groupingBy{it.type}.eachCount();val known=result.count{it.type!="unknown"};val leveled=result.count{it.level in 1..5};val candidateCount=result.count{it.confidence>=68&&it.type!="unknown"&&it.level in 1..5};val unknownCount=result.count{it.type=="unknown"}
        Log.i("LordsAssistantDiag","raw=${raw.size} grid=${result.size} candidates=$candidateCount independent=$added known=$known leveled=$leveled types=$typeCounts unknown=$unknownCount");result.forEachIndexed{index,d->Log.d("LordsAssistantDiag","tile#$index type=${d.type} level=${d.level} x=${d.centerX} y=${d.centerY} confidence=${d.confidence} occupied=${d.occupied} moving=${d.moving}")};return result
    }
    private fun classifyBadge(bitmap:Bitmap,badge:Badge,sx:Float,sy:Float):RssDetection{val probeX=(badge.box.centerX-(18f*sx).toInt()).coerceIn(8,bitmap.width-9);val probeY=(badge.box.centerY-(7f*sy).toInt()).coerceIn(8,bitmap.height-9);val visual=resourceClassifier.classify(bitmap,probeX,probeY);val type=when(visual.type){ResourceTileClassifier.Type.FOOD->"food";ResourceTileClassifier.Type.TIMBER->"timber";ResourceTileClassifier.Type.STONE->"stone";ResourceTileClassifier.Type.ORE->"ore";ResourceTileClassifier.Type.GOLD->"gold";else->"unknown"};val level=readBadgeLevel(bitmap,badge.box,sx,sy);val cc=if(visual.type==ResourceTileClassifier.Type.UNKNOWN)0 else min(badge.confidence,visual.confidence);val confidence=when{level in 1..5&&cc>=45->min(95,cc+8);level in 1..5->min(90,badge.confidence);else->cc};return RssDetection(type,level,probeX,probeY,badge.box,confidence,false,visual.redEvidence)}
    private fun readBadgeLevel(bitmap:Bitmap,box:BoundingBox,sx:Float,sy:Float):Int{val px=max(3,(5f*sx).toInt());val py=max(3,(5f*sy).toInt());val l=(box.minX-px).coerceAtLeast(0);val t=(box.minY-py).coerceAtLeast(0);val r=(box.maxX+px).coerceAtMost(bitmap.width-1);val b=(box.maxY+py).coerceAtMost(bitmap.height-1);if(r<=l||b<=t)return 0;return try{val crop=Bitmap.createBitmap(bitmap,l,t,r-l+1,b-t+1);val scaled=Bitmap.createScaledBitmap(crop,max(48,crop.width*4),max(48,crop.height*4),true);val text=try{Tasks.await(badgeRecognizer.process(InputImage.fromBitmap(scaled,0)),LEVEL_OCR_TIMEOUT_MS,TimeUnit.MILLISECONDS)?.text?:""}catch(_:Exception){""};try{scaled.recycle()}catch(_:Exception){};try{crop.recycle()}catch(_:Exception){};Regex("[1-5]").find(text)?.value?.toIntOrNull()?:0}catch(_:Exception){0}}
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int,sx:Float,sy:Float,ignoredRegions:List<BoundingBox>):List<Badge>{return collectBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions,false)+collectBadges(bitmap,left,right,top,bottom,sx,sy,ignoredRegions,true)}
    private fun collectBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int,sx:Float,sy:Float,ignoredRegions:List<BoundingBox>,relaxed:Boolean):List<Badge>{val width=right-left+1;val height=bottom-top+1;val size=width*height;val pixels=IntArray(size);bitmap.getPixels(pixels,0,width,left,top,width,height);val blue=BooleanArray(size);for(i in 0 until size){val c=pixels[i];val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);blue[i]=b>=(if(relaxed)40 else BLUE_MIN)&&b-r>=(if(relaxed)8 else BLUE_DELTA_R)&&b-g>=(if(relaxed)-2 else BLUE_DELTA_G)&&r<=(if(relaxed)215 else RED_MAX)};val out=ArrayList<Badge>();val visited=BooleanArray(size);val q=IntArray(size);for(y in 0 until height)for(x in 0 until width){val s=y*width+x;if(visited[s]||!blue[s])continue;var h=0;var t=0;q[t++]=s;visited[s]=true;var x1=x;var x2=x;var y1=y;var y2=y;var area=0;while(h<t){val p=q[h++];val py=p/width;val px=p%width;x1=min(x1,px);x2=max(x2,px);y1=min(y1,py);y2=max(y2,py);area++;for(dy in -1..1)for(dx in -1..1)if(dx!=0||dy!=0){val nx=px+dx;val ny=py+dy;if(nx in 0 until width&&ny in 0 until height){val ni=ny*width+nx;if(!visited[ni]&&blue[ni]){visited[ni]=true;q[t++]=ni}}}};val bw=x2-x1+1;val bh=y2-y1+1;val minW=max(7,(10*sx).toInt());val maxW=max(minW+2,(60*sx).toInt());val minH=max(6,(9*sy).toInt());val maxH=max(minH+2,(46*sy).toInt());val minA=max(30,(55*sx*sy).toInt());if(bw !in minW..maxW||bh !in minH..maxH||area<minA)continue;val density=area.toFloat()/(bw*bh);if(density<(if(relaxed).07f else .10f)||density>.97f)continue;val box=BoundingBox(left+x1,top+y1,left+x2,top+y2);if(ignoredRegions.any{box.minX<=it.maxX&&box.maxX>=it.minX&&box.minY<=it.maxY&&box.maxY>=it.minY})continue;val conf=(if(relaxed)58 else 64)+((16*(1f-abs(bw.toFloat()/bh-1.35f)/1.9f).coerceIn(0f,1f))+(12*(1f-abs(density-.45f)/.60f).coerceIn(0f,1f))).toInt();out+=Badge(box,conf.coerceIn(58,96))};return out.sortedWith(compareBy({it.box.centerY},{it.box.centerX}))}
    fun close(){try{viewportRecognizer.close()}catch(_:Exception){};try{badgeRecognizer.close()}catch(_:Exception){};ViewportOcrCache.clear()}
}