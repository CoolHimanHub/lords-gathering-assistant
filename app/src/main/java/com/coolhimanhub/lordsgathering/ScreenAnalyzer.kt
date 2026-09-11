package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

/**
 * V26 RSS detector.
 *
 * The supplied gameplay frames show that the RSS level marker is a compact
 * BLUE badge with a white digit. Enemy/monster markers can be red or dark and
 * must not be treated as RSS. The previous implementation searched for white
 * glyphs first and only loosely checked nearby blue pixels; that produced
 * false badges and, importantly, mapped visible 3/4/6 markers to L5.
 *
 * V26 therefore makes the badge geometry the primary gate: find compact blue
 * badge components, require a centered white numeral, then classify the
 * numeral using normalized 5x7 templates. Artwork/type remains a separate
 * gate. Occupancy and motion are NOT inferred from one frame; those states
 * must be verified temporally / from the opened tile panel before an action.
 */
class ScreenAnalyzer {
    data class BoundingBox(val minX:Int,val minY:Int,val maxX:Int,val maxY:Int) {
        val width:Int get()=maxX-minX+1
        val height:Int get()=maxY-minY+1
        val centerX:Int get()=(minX+maxX)/2
        val centerY:Int get()=(minY+maxY)/2
    }
    data class RssDetection(
        val type:String,
        val level:Int,
        val centerX:Int,
        val centerY:Int,
        val boundingBox:BoundingBox,
        val confidence:Int,
        val occupied:Boolean,
        val dominantColor:Int,
        val moving:Boolean=false,
        val movingScore:Int=0
    )
    private data class Badge(val box:BoundingBox,val blueRatio:Float,val digit:Int)
    private data class Artwork(val type:String,val confidence:Int,val box:BoundingBox,val dominant:Int,val footprint:Float,val red:Float,val green:Float,val blue:Float,val gray:Float,val yellow:Float,val orange:Float)
    private data class Blob(val box:BoundingBox,val area:Int,val dist:Double,val stats:Stats)
    private data class Stats(val red:Float,val green:Float,val blue:Float,val gray:Float,val yellow:Float,val orange:Float)

    companion object {
        private const val OCCUPIED_THRESHOLD=78
        private const val MIN_CONFIDENCE=60
    }

    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400)return emptyList()
        val scale=(bitmap.width/1536f).coerceAtLeast(.5f)
        val left=max(0,expectedRegionX.first)
        val right=min(bitmap.width-1,expectedRegionX.last)
        val top=max((55f*scale).toInt(),expectedRegionY.first)
        val bottom=min(bitmap.height-1-((121f*scale).toInt()),expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        val result=ArrayList<RssDetection>()
        for(badge in findBadges(bitmap,left,right,top,bottom)){
            val artwork=findArtwork(bitmap,badge.box,left,top,right,bottom)?:continue
            val confidence=artwork.confidence
            if(confidence<MIN_CONFIDENCE)continue
            result+=RssDetection(
                artwork.type,badge.digit,artwork.box.centerX,artwork.box.centerY,
                artwork.box,confidence,false,artwork.dominant,false,0
            )
        }
        return dedupe(result)
    }

    /**
     * Locate the actual blue level badge first. This avoids white UI text,
     * monster labels, and terrain highlights being promoted to candidates.
     */
    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val step=2
        val gw=(right-left)/step+1
        val gh=(bottom-top)/step+1
        val visited=BooleanArray(gw*gh)
        val queue=ArrayDeque<Int>()
        val found=ArrayList<Badge>()

        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            // Saturated game badge blue. Red enemy badges and green terrain do
            // not satisfy this hue relationship.
            return b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f
        }
        fun whiteAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y)
            val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            return r>=145&&g>=145&&b>=145&&max(r,max(g,b))-min(r,min(g,b))<115
        }

        for(gy in 0 until gh)for(gx in 0 until gw){
            val start=gy*gw+gx
            if(visited[start])continue
            val sx=min(right,left+gx*step);val sy=min(bottom,top+gy*step)
            if(!blueAt(sx,sy)){visited[start]=true;continue}
            queue.clear();queue.add(start);visited[start]=true
            var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var count=0
            while(queue.isNotEmpty()){
                val p=queue.removeFirst();val py=p/gw;val px=p%gw;count++
                minGX=min(minGX,px);maxGX=max(maxGX,px);minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy
                    if(nx !in 0 until gw||ny !in 0 until gh)continue
                    val ni=ny*gw+nx;if(visited[ni])continue
                    val xx=min(right,left+nx*step);val yy=min(bottom,top+ny*step)
                    visited[ni]=true
                    if(blueAt(xx,yy))queue.add(ni)
                }
            }

            // A real RSS badge in the supplied 1536x707 frames is roughly
            // 24-36 px wide and 18-28 px high. Allow scale variation but reject
            // large UI panels/icons.
            if(count !in 35..700)continue
            val box=BoundingBox(
                max(left,left+minGX*step-1),max(top,top+minGY*step-1),
                min(right,left+(maxGX+1)*step+1),min(bottom,top+(maxGY+1)*step+1)
            )
            if(box.width !in 20..42||box.height !in 15..32)continue

            val glyph=findCenteredWhiteGlyph(bitmap,box,::whiteAt)?:continue
            val digit=readBadgeDigit(bitmap,glyph)?:continue
            val ratio=blueRatio(bitmap,box)
            if(ratio<.22f)continue
            found+=Badge(box,ratio,digit)
        }

        return found.sortedWith(compareBy({it.box.centerY},{it.box.centerX})).fold(ArrayList()){acc,b->
            if(acc.none{abs(it.box.centerX-b.box.centerX)+abs(it.box.centerY-b.box.centerY)<20})acc.add(b)
            acc
        }
    }

    private fun findCenteredWhiteGlyph(bitmap:Bitmap,badge:BoundingBox,whiteAt:(Int,Int)->Boolean):BoundingBox?{
        val w=badge.width;val h=badge.height
        val l=badge.minX+max(1,w/5);val r=badge.maxX-max(1,w/8)
        val t=badge.minY+max(1,h/10);val b=badge.maxY-max(1,h/10)
        if(r<=l||b<=t)return null
        val step=1
        val gw=r-l+1;val gh=b-t+1
        val seen=BooleanArray(gw*gh);val q=ArrayDeque<Int>()
        var best:BoundingBox?=null;var bestArea=0
        for(y in 0 until gh)for(x in 0 until gw){
            val idx=y*gw+x;if(seen[idx])continue
            val xx=l+x;val yy=t+y
            if(!whiteAt(xx,yy)){seen[idx]=true;continue}
            q.clear();q.add(idx);seen[idx]=true
            var minX=x;var maxX=x;var minY=y;var maxY=y;var area=0
            while(q.isNotEmpty()){
                val p=q.removeFirst();val py=p/gw;val px=p%gw;area++
                minX=min(minX,px);maxX=max(maxX,px);minY=min(minY,py);maxY=max(maxY,py)
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy
                    if(nx !in 0 until gw||ny !in 0 until gh)continue
                    val ni=ny*gw+nx;if(seen[ni])continue
                    val ax=l+nx;val ay=t+ny;seen[ni]=true
                    if(whiteAt(ax,ay))q.add(ni)
                }
            }
            val bw=maxX-minX+1;val bh=maxY-minY+1
            val cx=(minX+maxX)/2f;val cy=(minY+maxY)/2f
            val centered=cx in w*.25f..w*.90f && cy in h*.10f..h*.95f
            if(area in 18..150 && bw in 6..18 && bh in 10..24 && centered && area>bestArea){
                best=BoundingBox(l+minX,t+minY,l+maxX,t+maxY);bestArea=area
            }
        }
        return best
    }

    /** Normalize the white glyph and compare against tolerant 5x7 digit masks. */
    private fun readBadgeDigit(bitmap:Bitmap,glyph:BoundingBox):Int?{
        val templates=mapOf(
            1 to arrayOf("00100","01100","00100","00100","00100","00100","01110"),
            2 to arrayOf("01110","10001","00001","00010","00100","01000","11111"),
            3 to arrayOf("11110","00001","00001","01110","00001","00001","11110"),
            4 to arrayOf("00010","00110","01010","10010","11111","00010","00010"),
            5 to arrayOf("11111","10000","10000","11110","00001","00001","11110"),
            6 to arrayOf("01110","10000","10000","11110","10001","10001","01110")
        )
        val gw=5;val gh=7
        val actual=Array(gh){BooleanArray(gw)}
        val w=glyph.width.toFloat();val h=glyph.height.toFloat()
        for(gy in 0 until gh)for(gx in 0 until gw){
            val xa=glyph.minX+(gx*w/5f).toInt();val xb=max(xa+1,glyph.minX+((gx+1)*w/5f).toInt())
            val ya=glyph.minY+(gy*h/7f).toInt();val yb=max(ya+1,glyph.minY+((gy+1)*h/7f).toInt())
            var on=0;var total=0
            for(y in ya until min(glyph.maxY+1,yb))for(x in xa until min(glyph.maxX+1,xb)){
                val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);total++
                if(r>=145&&g>=145&&b>=145&&max(r,max(g,b))-min(r,min(g,b))<115)on++
            }
            actual[gy][gx]=total>0&&on*100>=total*10
        }
        var bestDigit:Int?=null;var best=999;var second=999
        for((digit,rows) in templates){
            var dist=0
            for(y in 0 until gh)for(x in 0 until gw){
                val expected=rows[y][x]=='1'
                if(actual[y][x]!=expected)dist++
            }
            if(dist<best){second=best;best=dist;bestDigit=digit}else if(dist<second)second=dist
        }
        // Anti-aliasing and the game's shadow/outline can alter a few cells,
        // but a correct glyph should still beat the next digit clearly.
        if(bestDigit==null||best>14||second-best<1)return null
        return bestDigit
    }

    /**
     * Video-derived geometry: RSS sprites are made of several nearby pieces,
     * so the old "nearest single blob" rule is too small. Build nearby blob
     * clusters and score the whole cluster.
     */
    private fun findArtwork(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Artwork?{
        val cx=badge.centerX;val cy=badge.centerY
        val bw=max(12,badge.width);val bh=max(10,badge.height)
        val l=max(left,cx-6*bw);val r=min(right,cx-bw/3);val t=max(top,cy-4*bh);val b=min(bottom,cy+4*bh)
        if(r<=l||b<=t)return null
        val step=2;val gw=(r-l)/step+1;val gh=(b-t)/step+1;val seen=BooleanArray(gw*gh);val q=ArrayDeque<Int>();val blobs=ArrayList<Blob>()
        fun fg(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));val ch=mx-mn
            val greenBackground=gg>rr*1.035f&&gg>bb*1.035f&&gg>62&&ch<85
            return !greenBackground&&mx>52&&ch>14
        }
        for(gy in 0 until gh)for(gx in 0 until gw){
            val idx=gy*gw+gx;if(seen[idx])continue;val x=l+gx*step;val y=t+gy*step
            if(!fg(x,y)){seen[idx]=true;continue}
            q.clear();q.add(idx);seen[idx]=true;var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var area=0
            while(q.isNotEmpty()){
                val p=q.removeFirst();val py=p/gw;val px=p%gw;area++;minGX=min(minGX,px);maxGX=max(maxGX,px);minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=px+dx;val ny=py+dy;if(nx !in 0 until gw||ny !in 0 until gh)continue;val ni=ny*gw+nx;if(seen[ni])continue;val xx=l+nx*step;val yy=t+ny*step;if(fg(xx,yy)){seen[ni]=true;q.add(ni)}}
            }
            if(area<10||area>2200)continue
            val box=BoundingBox(max(l,l+minGX*step-1),max(t,t+minGY*step-1),min(r,l+(maxGX+1)*step+1),min(b,t+(maxGY+1)*step+1))
            if(box.width<6||box.height<6||box.width>6*bw||box.height>5*bh)continue
            val dist=hypot(box.centerX-cx.toDouble(),box.centerY-cy.toDouble())
            if(dist>4.8*bw||box.centerX>=cx-bw*.05f)continue
            blobs+=Blob(box,area,dist,stats(bitmap,box))
        }
        if(blobs.isEmpty())return null
        data class Cluster(val box:BoundingBox,val area:Int,val stats:Stats,val dist:Double)
        val clusters=ArrayList<Cluster>()
        for(seed in blobs){
            val members=blobs.filter{hypot(it.box.centerX-seed.box.centerX.toDouble(),it.box.centerY-seed.box.centerY.toDouble())<=2.8*bw}
            var minX=seed.box.minX;var minY=seed.box.minY;var maxX=seed.box.maxX;var maxY=seed.box.maxY;var area=0
            for(m in members){minX=min(minX,m.box.minX);minY=min(minY,m.box.minY);maxX=max(maxX,m.box.maxX);maxY=max(maxY,m.box.maxY);area+=m.area}
            val ub=BoundingBox(minX,minY,maxX,maxY)
            if(ub.centerX>=cx-bw*.05f)continue
            clusters+=Cluster(ub,area,stats(bitmap,ub),hypot(ub.centerX-cx.toDouble(),ub.centerY-cy.toDouble()))
        }
        val chosen=clusters.sortedWith(compareByDescending<Cluster>{it.area}.thenBy{it.dist}).firstOrNull()?:return null
        val s=chosen.stats
        if(s.red>.42f&&s.orange>.30f&&s.blue<.22f)return null
        val scores=mapOf(
            "Ore" to (s.blue*120f+s.gray*28f-s.red*25f-s.orange*12f),
            "Stone" to (s.gray*115f+s.blue*24f-s.orange*30f-s.red*18f),
            "Wood" to (s.green*75f+s.orange*50f+s.yellow*18f-s.blue*18f-s.red*18f),
            "Food" to (s.yellow*125f+s.orange*60f+s.green*20f-s.blue*25f-s.red*12f)
        ).toList().sortedByDescending{it.second}
        val best=scores[0];val second=scores[1]
        if(best.second<18f||best.second-second.second<3f)return null
        val footprint=(chosen.area.toFloat()/max(1,chosen.box.width*chosen.box.height)).coerceIn(0f,1f)
        val conf=(54f+best.second*.40f+footprint*16f).toInt().coerceIn(0,96)
        return Artwork(best.first,conf,chosen.box,dominant(bitmap,chosen.box),footprint,s.red,s.green,s.blue,s.gray,s.yellow,s.orange)
    }

    private fun stats(bitmap:Bitmap,box:BoundingBox):Stats{
        var n=0;var red=0;var green=0;var blue=0;var gray=0;var yellow=0;var orange=0
        for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){
            val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);val mx=max(r,max(g,b));val mn=min(r,min(g,b));val ch=mx-mn;n++
            if(r>g*1.15f&&r>b*1.15f&&r>90)red++
            if(g>r*1.05f&&g>b*1.05f&&g>65)green++
            if(b>r*1.08f&&b>=g*.96f&&ch>28)blue++
            if(ch<52&&mx in 80..225)gray++
            if(r>145&&g>125&&b<140&&g>b*1.10f)yellow++
            if(r>g*1.05f&&g>b*1.15f&&r>100)orange++
        }
        if(n==0)return Stats(0f,0f,0f,0f,0f,0f)
        return Stats(red.toFloat()/n,green.toFloat()/n,blue.toFloat()/n,gray.toFloat()/n,yellow.toFloat()/n,orange.toFloat()/n)
    }

    private fun dominant(bitmap:Bitmap,box:BoundingBox):Int{var r=0;var g=0;var b=0;var n=0;for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){val c=bitmap.getPixel(x,y);r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);n++};return if(n==0)Color.BLACK else Color.rgb(r/n,g/n,b/n)}
    private fun blueRatio(bitmap:Bitmap,box:BoundingBox):Float{var q=0;var n=0;for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){n++;val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);if(b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f)q++};return if(n==0)0f else q.toFloat()/n}
    private fun dedupe(input:List<RssDetection>):List<RssDetection>{val out=ArrayList<RssDetection>();for(d in input)if(out.none{abs(d.centerX-it.centerX)+abs(d.centerY-it.centerY)<24})out+=d;return out.sortedWith(compareByDescending<RssDetection>{it.confidence}.thenByDescending{it.level})}
}
