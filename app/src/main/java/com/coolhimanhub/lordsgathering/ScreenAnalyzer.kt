package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** V27 - regression-driven RSS detector. */
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
    private data class Badge(val box:BoundingBox,val blueRatio:Float,val digit:Int,val digitScore:Int)
    private data class TypeResult(val name:String,val confidence:Int,val avg:Int)

    companion object { private const val MIN_ACCEPT=78; private const val BLUE_MIN=70 }

    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400)return emptyList()
        val left=max(0,expectedRegionX.first);val right=min(bitmap.width-1,expectedRegionX.last)
        val top=max(45,expectedRegionY.first);val bottom=min(bitmap.height-120,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()
        val out=ArrayList<RssDetection>()
        for(badge in findBadges(bitmap,left,right,top,bottom)){
            val art=classifyArtwork(bitmap,badge.box,left,top,right,bottom)?:continue
            val occupied=detectCompactRedMarker(bitmap,badge.box)
            var confidence=(badge.digitScore*.30f+art.confidence*.58f+badge.blueRatio*100f*.12f).toInt()
            if(occupied)confidence-=10
            confidence=confidence.coerceIn(0,100)
            if(confidence<MIN_ACCEPT)continue
            out+=RssDetection(art.name,badge.digit,badge.box.centerX,badge.box.centerY,badge.box,confidence,occupied,art.avg)
        }
        return dedupe(out)
    }

    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val step=2;val gw=(right-left)/step+1;val gh=(bottom-top)/step+1
        val visited=BooleanArray(gw*gh);val found=ArrayList<Badge>();val queue=IntArray(gw*gh)
        fun blueAt(x:Int,y:Int):Boolean{val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);return b>=BLUE_MIN&&b-r>=12&&b>=g*.94f}
        fun whiteAt(x:Int,y:Int):Boolean{val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);val mx=max(r,max(g,b));val mn=min(r,min(g,b));return mn>=130&&mx>=165&&mx-mn<=115}
        for(gy in 0 until gh)for(gx in 0 until gw){
            val start=gy*gw+gx;if(visited[start])continue;val sx=left+gx*step;val sy=top+gy*step
            if(!blueAt(sx,sy)){visited[start]=true;continue}
            var head=0;var tail=0;queue[tail++]=start;visited[start]=true
            var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var area=0
            while(head<tail){
                val p=queue[head++];val py=p/gw;val px=p%gw;area++;minGX=min(minGX,px);maxGX=max(maxGX,px);minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=px+dx;val ny=py+dy;if(nx !in 0 until gw||ny !in 0 until gh)continue;val ni=ny*gw+nx;if(visited[ni])continue;visited[ni]=true;if(blueAt(left+nx*step,top+ny*step))queue[tail++]=ni}
            }
            if(area !in 20..700)continue
            val box=BoundingBox(max(left,left+minGX*step-1),max(top,top+minGY*step-1),min(right,left+(maxGX+1)*step+1),min(bottom,top+(maxGY+1)*step+1))
            if(box.width !in 20..42||box.height !in 15..32)continue
            val glyph=findWhiteGlyph(bitmap,box,::whiteAt)?:continue;val read=readDigit(bitmap,glyph)?:continue
            val ratio=blueRatio(bitmap,box,::blueAt);if(ratio<.20f)continue;found+=Badge(box,ratio,read.first,read.second)
        }
        val sorted=found.sortedWith(compareBy({it.box.centerY},{it.box.centerX}));val out=ArrayList<Badge>()
        for(b in sorted)if(out.none{abs(it.box.centerX-b.box.centerX)<18&&abs(it.box.centerY-b.box.centerY)<18})out+=b
        return out
    }

    private fun findWhiteGlyph(bitmap:Bitmap,badge:BoundingBox,whiteAt:(Int,Int)->Boolean):BoundingBox?{
        val l=badge.minX+max(2,badge.width/6);val r=badge.maxX-max(2,badge.width/10);val t=badge.minY+max(1,badge.height/10);val b=badge.maxY-max(1,badge.height/10)
        if(r<=l||b<=t)return null;val w=r-l+1;val h=b-t+1;val seen=BooleanArray(w*h);val q=IntArray(w*h);var best:BoundingBox?=null;var bestArea=0
        for(yy in 0 until h)for(xx in 0 until w){val idx=yy*w+xx;if(seen[idx])continue;val px=l+xx;val py=t+yy;if(!whiteAt(px,py)){seen[idx]=true;continue}
            var head=0;var tail=0;q[tail++]=idx;seen[idx]=true;var minX=xx;var maxX=xx;var minY=yy;var maxY=yy;var area=0
            while(head<tail){val p=q[head++];val cy=p/w;val cx=p%w;area++;minX=min(minX,cx);maxX=max(maxX,cx);minY=min(minY,cy);maxY=max(maxY,cy);for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=cx+dx;val ny=cy+dy;if(nx !in 0 until w||ny !in 0 until h)continue;val ni=ny*w+nx;if(seen[ni])continue;seen[ni]=true;if(whiteAt(l+nx,t+ny))q[tail++]=ni}}
            val bw=maxX-minX+1;val bh=maxY-minY+1;if(area in 18..160&&bw in 6..18&&bh in 10..24&&bh>=bw&&area>bestArea){bestArea=area;best=BoundingBox(l+minX,t+minY,l+maxX,t+maxY)}
        };return best
    }

    private fun readDigit(bitmap:Bitmap,glyph:BoundingBox):Pair<Int,Int>?{
        val masks=mapOf(1 to arrayOf("00100","01100","00100","00100","00100","00100","01110"),2 to arrayOf("01110","10001","00001","00010","00100","01000","11111"),3 to arrayOf("11110","00001","00001","01110","00001","00001","11110"),4 to arrayOf("00010","00110","01010","10010","11111","00010","00010"),5 to arrayOf("11111","10000","10000","11110","00001","00001","11110"),6 to arrayOf("01110","10000","10000","11110","10001","10001","01110"))
        val actual=sampleGlyph(bitmap,glyph);val scores=ArrayList<Pair<Int,Int>>()
        for((digit,mask) in masks){var diff=0;for(y in 0 until 7)for(x in 0 until 5)if(actual[y][x]!=(mask[y][x]=='1'))diff++;scores+=digit to (diff*7-projectionScore(actual,digit))}
        scores.sortBy{it.second};val best=scores[0];val second=scores[1];val margin=second.second-best.second;val rawDiff=(best.second+projectionScore(actual,best.first))/7
        if(rawDiff>18||margin<5)return null;return best.first to (100-rawDiff*4+margin*2).coerceIn(72,98)
    }

    private fun sampleGlyph(bitmap:Bitmap,box:BoundingBox):Array<BooleanArray>{
        val out=Array(7){BooleanArray(5)};val w=box.width.toFloat();val h=box.height.toFloat()
        for(gy in 0 until 7)for(gx in 0 until 5){val x0=box.minX+(gx*w/5f).toInt();val x1=max(x0+1,box.minX+((gx+1)*w/5f).toInt());val y0=box.minY+(gy*h/7f).toInt();val y1=max(y0+1,box.minY+((gy+1)*h/7f).toInt());var on=0;var total=0;for(y in y0 until min(box.maxY+1,y1))for(x in x0 until min(box.maxX+1,x1)){val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);val mx=max(r,max(g,b));val mn=min(r,min(g,b));total++;if(mn>=125&&mx>=160&&mx-mn<=120)on++};out[gy][gx]=total>0&&on*100>=total*16}
        return out
    }

    private fun projectionScore(a:Array<BooleanArray>,d:Int):Int {
        fun row(y:Int):Int = (0 until 5).count { a[y][it] }
        fun col(x:Int):Int = (0 until 7).count { a[it][x] }
        var s=0
        when(d){
            1->{if(col(2)>=4)s+=8;if(row(6)>=3)s+=3}
            2->{if(row(0)>=3)s+=5;if(row(3)>=2)s+=4;if(row(6)>=3)s+=5;if(col(4)>=4)s+=3;if(col(0)<=2)s+=2}
            3->{if(row(0)>=3)s+=5;if(row(3)>=2)s+=5;if(row(6)>=3)s+=5;if(col(4)>=4)s+=4;if(col(0)<=2)s+=2}
            4->{if(row(4)>=3)s+=6;if(col(4)>=5)s+=4;if(col(0)>=2)s+=2;if(row(0)<=2)s+=2;if(row(6)<=2)s+=2}
            5->{if(row(0)>=3)s+=5;if(row(3)>=3)s+=4;if(row(6)>=3)s+=5;if(col(0)>=3)s+=3;if(col(4)>=3)s+=2}
            6->{if(row(3)>=3)s+=5;if(row(6)>=3)s+=4;if(col(0)>=4)s+=3;if(col(4)>=3)s+=2}
        }
        return s
    }

    private fun classifyArtwork(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):TypeResult?{
        val cx=badge.centerX;val l=max(left,cx-90);val r=min(right,cx+12);val t=max(top,badge.minY-82);val b=min(bottom,badge.maxY+5);if(r<=l||b<=t)return null
        var cyan=0;var brown=0;var yellow=0;var grey=0;var green=0;var strong=0;var total=0;var sr=0L;var sg=0L;var sb=0L
        for(y in t..b step 2)for(x in l..r step 2){if(x in badge.minX..badge.maxX&&y in badge.minY..badge.maxY)continue;val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));val spread=mx-mn;if(mx<55)continue;total++;sr+=rr;sg+=gg;sb+=bb;if(spread>55)strong++;if(bb>rr*1.16f&&bb>gg*1.01f&&bb>90)cyan++;if(rr>gg*1.12f&&gg>bb*1.02f&&rr>80)brown++;if(rr>145&&gg>120&&bb<gg*.88f)yellow++;if(gg>rr*1.05f&&gg>bb*1.06f&&spread>25)green++;if(spread<42&&mx in 75..210)grey++}
        if(total<40)return null
        val scores=ArrayList<Pair<String,Int>>();val ore=(cyan*120/total+min(18,strong)).coerceAtMost(94);val stone=(grey*115/total+min(20,strong*2)).coerceAtMost(94);val wood=(brown*115/total+min(16,green*20/total)).coerceAtMost(94);val food=(yellow*120/total+min(18,strong*2)).coerceAtMost(94)
        if(cyan*100>=total*14&&ore>=48)scores+="Ore" to ore;if(grey*100>=total*22&&stone>=48)scores+="Stone" to stone;if(brown*100>=total*11&&wood>=48)scores+="Wood" to wood;if(yellow*100>=total*10&&food>=48)scores+="Food" to food
        if(scores.isEmpty())return null;scores.sortByDescending{it.second};val best=scores[0];val second=scores.getOrNull(1)?.second?:0;if(second>0&&best.second-second<12||best.second<58)return null
        return TypeResult(best.first,best.second,Color.rgb((sr/total).toInt().coerceIn(0,255),(sg/total).toInt().coerceIn(0,255),(sb/total).toInt().coerceIn(0,255)))
    }

    private fun detectCompactRedMarker(bitmap:Bitmap,badge:BoundingBox):Boolean{val l=max(0,badge.minX-42);val r=min(bitmap.width-1,badge.maxX+30);val t=max(0,badge.minY-32);val b=min(bitmap.height-1,badge.maxY+42);var red=0;var total=0;for(y in t..b step 2)for(x in l..r step 2){val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);total++;if(rr>205&&rr>gg*1.65f&&rr>bb*1.65f&&gg<120&&bb<120)red++};return red>=6&&red*100>total*2}
    private fun blueRatio(bitmap:Bitmap,box:BoundingBox,blueAt:(Int,Int)->Boolean):Float{var n=0;var total=0;for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){total++;if(blueAt(x,y))n++};return if(total==0)0f else n.toFloat()/total}
    private fun dedupe(items:List<RssDetection>):List<RssDetection>{val out=ArrayList<RssDetection>();for(item in items.sortedByDescending{it.confidence})if(out.none{abs(it.centerX-item.centerX)<30&&abs(it.centerY-item.centerY)<30})out+=item;return out}
}
