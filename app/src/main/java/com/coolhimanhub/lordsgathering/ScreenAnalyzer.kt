package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int) {
        val width:Int get()=maxX-minX+1
        val height:Int get()=maxY-minY+1
        val centerX:Int get()=(minX+maxX)/2
        val centerY:Int get()=(minY+maxY)/2
    }
    data class RssDetection(val type:String,val level:Int,val centerX:Int,val centerY:Int,val boundingBox:BoundingBox,val confidence:Int,val occupied:Boolean,val dominantColor:Int,val moving:Boolean=false,val movingScore:Int=0)
    private data class Badge(val box:BoundingBox,val ratio:Float,val digit:Int)
    companion object { private const val OCCUPIED_THRESHOLD=28; private const val MOVING_THRESHOLD=34 }

    fun analyzeScreenshot(bitmap:Bitmap, expectedRegionX:IntRange=0 until bitmap.width, expectedRegionY:IntRange=0 until bitmap.height):List<RssDetection> {
        if(bitmap.width<600||bitmap.height<400)return emptyList()
        val left=max(105,expectedRegionX.first); val right=min(1290,min(bitmap.width-1,expectedRegionX.last)); val top=max(65,expectedRegionY.first); val bottom=min(bitmap.height-120,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()
        val out=ArrayList<RssDetection>()
        for(b in findBadges(bitmap,left,right,top,bottom)) {
            val flag=flagScore(bitmap,b.box,left,top,right,bottom); val occupied=flag>=OCCUPIED_THRESHOLD
            val move=movingScore(bitmap,b.box,left,top,right,bottom); val moving=move>=MOVING_THRESHOLD
            val art=classifyArtwork(bitmap,b.box,left,top,right,bottom) ?: continue
            var conf=(b.ratio*100f*.28f+art.second*.56f+localQuality(bitmap,b.box,art.first)*.16f).toInt()
            if(occupied)conf-=8; if(moving)conf-=5; conf=conf.coerceIn(0,100)
            if(conf>=72)out+=RssDetection(art.third,b.digit,b.box.centerX,b.box.centerY,b.box,conf,occupied,art.fourth,moving,move)
        }
        return dedupe(out)
    }

    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val out=ArrayList<Badge>()
        for(y in top..bottom step 2)for(x in left..right step 2){
            if(!isBlue(bitmap.getPixel(x,y)))continue
            val box=BoundingBox(max(left,x-10),max(top,y-7),min(right,x+10),min(bottom,y+7)); if(box.width !in 18..58||box.height !in 12..42)continue
            val ratio=blueRatio(bitmap,box); if(ratio<.16f)continue; val digit=readBadgeDigit(bitmap,box)?:continue
            if(out.none{absCenter(it.box,box)<14})out+=Badge(box,ratio,digit)
        }
        return out
    }

    private fun readBadgeDigit(bitmap:Bitmap,box:BoundingBox):Int?{
        val cx=box.centerX; val cy=box.centerY; var bright=0; var total=0
        for(y in max(box.minY,cy-12)..min(box.maxY,cy+12))for(x in max(box.minX,cx-12)..min(box.maxX,cx+12)){val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);total++;if(r>=155&&g>=155&&b>=155&&max(r,max(g,b))-min(r,min(g,b))<110)bright++}
        if(total==0||bright<12)return null
        return (2..5).minByOrNull{n->kotlin.math.abs(bright-total*(0.04f+n*.006f))}
    }

    private fun classifyArtwork(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Quad?{
        val l=max(left,badge.minX-82);val r=min(right,badge.minX-3);val t=max(top,badge.centerY-48);val b=min(bottom,badge.maxY+20);if(r<=l||b<=t)return null
        var cyan=0;var warm=0;var yellow=0;var gray=0;var total=0;var sr=0;var sg=0;var sb=0
        for(y in t..b step 2)for(x in l..r step 2){val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));val ch=mx-mn;total++;sr+=rr;sg+=gg;sb+=bb;if(bb>rr*1.08f&&bb>=gg*.96f&&ch>28)cyan++;if(rr>gg*1.08f&&gg>bb*1.02f&&rr>80)warm++;if(rr>145&&gg>125&&bb<135&&gg>bb*1.12f)yellow++;if(ch<48&&mx in 80..225)gray++}
        if(total<30)return null
        val cr=cyan.toFloat()/total;val wr=warm.toFloat()/total;val yr=yellow.toFloat()/total;val gr=gray.toFloat()/total
        val c=listOf("Ore" to cr*120+wr*30,"Wood" to wr*105+yr*20,"Food" to yr*120+wr*20,"Stone" to gr*115+(1f-cr)*20).sortedByDescending{it.second};if(c[0].second<10f||c[0].second-c[1].second<4f)return null
        return Quad(BoundingBox(l,t,r,b),(55+c[0].second*1.35f).toInt().coerceIn(0,94),c[0].first,Color.rgb(sr/total,sg/total,sb/total))
    }
    private data class Quad(val first:BoundingBox,val second:Int,val third:String,val fourth:Int)

    private fun flagScore(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Int{val l=max(left,badge.minX-24);val r=min(right,badge.maxX+30);val t=max(top,badge.minY-40);val b=min(bottom,badge.minY+10);var red=0;var strong=0;var topRed=0;var n=0;for(y in t..b step 2)for(x in l..r step 2){val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);n++;if(rr>150&&rr>gg*1.35f&&rr>bb*1.35f){red++;if(rr>200)strong++;if(y<badge.centerY)topRed++}};if(n==0)return 0;var s=0;if(red>=5)s+=18;if(red>=12)s+=18;if(strong>=3)s+=12;if(topRed>=3)s+=15;if(red>100)s-=20;return s.coerceIn(0,100)}

    private fun movingScore(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Int{val cx=badge.centerX;val cy=badge.centerY;val dirs=arrayOf(intArrayOf(1,0),intArrayOf(-1,0),intArrayOf(0,1),intArrayOf(0,-1),intArrayOf(1,1),intArrayOf(-1,1),intArrayOf(1,-1),intArrayOf(-1,-1));var best=0;for(d in dirs){var hits=0;var run=0;var maxRun=0;for(dist in 18..105 step 3){val x=cx+d[0]*dist;val y=cy+d[1]*dist;if(x !in left..right||y !in top..bottom)break;var hit=false;for(oy in -2..2)for(ox in -2..2){val xx=x+ox;val yy=y+oy;if(xx !in left..right||yy !in top..bottom)continue;val c=bitmap.getPixel(xx,yy);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));if(mx-mn>65&&mx>125)hit=true};if(hit){hits++;run++;maxRun=max(maxRun,run)}else run=0};best=max(best,(hits*5+maxRun*4).coerceAtMost(100))};return best}

    private fun localQuality(bitmap:Bitmap,a:BoundingBox,b:BoundingBox):Int{val x=max(a.minX,b.minX);val y=max(a.minY,b.minY);val xx=min(a.maxX,b.maxX);val yy=min(a.maxY,b.maxY);if(xx<x||yy<y)return 0;var q=0;var n=0;for(py in y..yy step 3)for(px in x..xx step 3){val c=bitmap.getPixel(px,py);if(max(Color.red(c),max(Color.green(c),Color.blue(c)))>70)q++;n++};return if(n==0)0 else q*100/n}
    private fun blueRatio(bitmap:Bitmap,box:BoundingBox):Float{var q=0;var n=0;for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){n++;if(isBlue(bitmap.getPixel(x,y)))q++};return if(n==0)0f else q.toFloat()/n}
    private fun isBlue(c:Int):Boolean{val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);return b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f}
    private fun absCenter(a:BoundingBox,b:BoundingBox)=kotlin.math.abs(a.centerX-b.centerX)+kotlin.math.abs(a.centerY-b.centerY)
    private fun dedupe(input:List<RssDetection>):List<RssDetection>{val o=ArrayList<RssDetection>();for(d in input)if(o.none{kotlin.math.abs(d.centerX-it.centerX)+kotlin.math.abs(d.centerY-it.centerY)<20})o+=d;return o}
}
