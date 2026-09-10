package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

/**
 * V24 RSS detector.
 *
 * The detector is deliberately conservative: a blue level badge is only the
 * first clue. The nearby map artwork must also have an RSS-like footprint and
 * colour signature. This prevents monsters, UI badges and moving units from
 * becoming gather targets.
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
        private const val OCCUPIED_THRESHOLD=58
        private const val MOVING_THRESHOLD=62
        private const val MIN_CONFIDENCE=64
    }

    fun analyzeScreenshot(bitmap:Bitmap,expectedRegionX:IntRange=0 until bitmap.width,expectedRegionY:IntRange=0 until bitmap.height):List<RssDetection>{
        if(bitmap.width<600||bitmap.height<400)return emptyList()
        val left=max(0,expectedRegionX.first)
        val right=min(bitmap.width-1,expectedRegionX.last)
        val top=max(55,expectedRegionY.first)
        val bottom=min(bitmap.height-121,expectedRegionY.last)
        if(right<=left||bottom<=top)return emptyList()

        val result=ArrayList<RssDetection>()
        for(badge in findBadges(bitmap,left,right,top,bottom)){
            val artwork=findArtwork(bitmap,badge.box,left,top,right,bottom)?:continue
            val occupied=flagScore(bitmap,badge.box,left,top,right,bottom)>=OCCUPIED_THRESHOLD
            val movement=movingScore(bitmap,badge.box,left,top,right,bottom)
            val moving=movement>=MOVING_THRESHOLD
            var confidence=(badge.blueRatio*100f*.20f+artwork.confidence*.80f).toInt()
            if(occupied)confidence-=35
            if(moving)confidence-=35
            confidence=confidence.coerceIn(0,100)
            if(occupied||moving||confidence<MIN_CONFIDENCE)continue
            result+=RssDetection(artwork.type,badge.digit,artwork.box.centerX,artwork.box.centerY,artwork.box,confidence,false,artwork.dominant,false,movement)
        }
        return dedupe(result)
    }

    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        val step=2
        val gw=(right-left)/step+1
        val gh=(bottom-top)/step+1
        val visited=BooleanArray(gw*gh)
        val queue=ArrayDeque<Int>()
        val found=ArrayList<Badge>()
        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            return b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f
        }
        for(gy in 0 until gh)for(gx in 0 until gw){
            val start=gy*gw+gx;if(visited[start])continue
            val sx=min(right,left+gx*step);val sy=min(bottom,top+gy*step)
            if(!blueAt(sx,sy)){visited[start]=true;continue}
            queue.clear();queue.add(start);visited[start]=true
            var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var count=0
            while(queue.isNotEmpty()){
                val p=queue.removeFirst();val py=p/gw;val px=p%gw;count++
                minGX=min(minGX,px);maxGX=max(maxGX,px);minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){
                    if(dx==0&&dy==0)continue
                    val nx=px+dx;val ny=py+dy;if(nx !in 0 until gw||ny !in 0 until gh)continue
                    val ni=ny*gw+nx;if(visited[ni])continue
                    val xx=min(right,left+nx*step);val yy=min(bottom,top+ny*step)
                    if(blueAt(xx,yy)){visited[ni]=true;queue.add(ni)}
                }
            }
            if(count !in 14..280)continue
            val box=BoundingBox(max(left,left+minGX*step-2),max(top,top+minGY*step-2),min(right,left+(maxGX+1)*step+2),min(bottom,top+(maxGY+1)*step+2))
            if(box.width !in 18..58||box.height !in 12..42)continue
            val aspect=box.width.toFloat()/box.height.toFloat();if(aspect !in .65f..2.9f)continue
            val ratio=blueRatio(bitmap,box);if(ratio<.14f)continue
            val digit=readBadgeDigit(bitmap,box)?:continue
            found+=Badge(box,ratio,digit)
        }
        return found.sortedWith(compareBy({it.box.centerY},{it.box.centerX})).fold(ArrayList()){acc,b->if(acc.none{abs(it.box.centerX-b.box.centerX)+abs(it.box.centerY-b.box.centerY)<18})acc.add(b);acc}
    }

    private fun readBadgeDigit(bitmap:Bitmap,box:BoundingBox):Int?{
        val inner=BoundingBox(max(box.minX+3,box.centerX-9),max(box.minY+2,box.centerY-10),min(box.maxX-3,box.centerX+9),min(box.maxY-2,box.centerY+10))
        if(inner.width<7||inner.height<9)return null
        val pts=ArrayList<Pair<Int,Int>>()
        for(y in inner.minY..inner.maxY)for(x in inner.minX..inner.maxX){
            val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            if(r>=150&&g>=150&&b>=150&&max(r,max(g,b))-min(r,min(g,b))<115)pts+=x to y
        }
        if(pts.size<12)return null
        val x0=pts.minOf{it.first};val x1=pts.maxOf{it.first};val y0=pts.minOf{it.second};val y1=pts.maxOf{it.second}
        val w=x1-x0+1;val h=y1-y0+1;if(w !in 3..17||h !in 8..24)return null
        val grid=Array(7){BooleanArray(5)}
        for(gy in 0 until 7)for(gx in 0 until 5){
            val xa=x0+gx*w/5;val xb=max(xa+1,x0+(gx+1)*w/5);val ya=y0+gy*h/7;val yb=max(ya+1,y0+(gy+1)*h/7)
            var on=0;var total=0
            for(yy in ya until min(y0+h,yb))for(xx in xa until min(x0+w,xb)){
                total++;val c=bitmap.getPixel(xx,yy);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
                if(r>=150&&g>=150&&b>=150&&max(r,max(g,b))-min(r,min(g,b))<115)on++
            }
            grid[gy][gx]=total>0&&on*100>=total*16
        }
        fun row(y:Int)=grid[y].count{it}/5f
        fun col(x:Int)=(0 until 7).count{grid[it][x]}/7f
        val top=(row(0)+row(1))/2f;val mid=(row(3)+row(4))/2f;val bot=(row(5)+row(6))/2f;val lu=(col(0)+col(1))/2f;val ru=(col(3)+col(4))/2f
        val scores=listOf(2 to top*26+mid*28+bot*30+ru*16+lu*17-lu*8-ru*6,3 to top*25+mid*30+bot*27+ru*20+ru*18-lu*12,4 to mid*34+ru*32+lu*17+ru*12-top*12-bot*14,5 to top*29+mid*29+bot*29+lu*20+ru*15-ru*8).sortedByDescending{it.second}
        if(scores[0].second<14f||scores[0].second-scores[1].second<2f)return null
        return scores[0].first
    }

    /** Find a compact object immediately to the left of the badge. */
    private fun findArtwork(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Artwork?{
        val cx=badge.centerX;val cy=badge.centerY
        val l=max(left,cx-82);val r=min(right,cx-4);val t=max(top,cy-45);val b=min(bottom,cy+45)
        if(r<=l||b<=t)return null
        val step=2;val gw=(r-l)/step+1;val gh=(b-t)/step+1;val mask=BooleanArray(gw*gh);val seen=BooleanArray(gw*gh);val q=ArrayDeque<Int>();val blobs=ArrayList<Blob>()
        fun fg(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));val ch=mx-mn
            val grass=gg>rr*1.04f&&gg>bb*1.04f&&gg>62
            return !grass&&mx>52&&ch>14
        }
        for(gy in 0 until gh)for(gx in 0 until gw){
            val idx=gy*gw+gx;if(seen[idx])continue;val x=l+gx*step;val y=t+gy*step
            if(!fg(x,y)){seen[idx]=true;continue}
            q.clear();q.add(idx);seen[idx]=true;var minGX=gx;var maxGX=gx;var minGY=gy;var maxGY=gy;var area=0
            while(q.isNotEmpty()){
                val p=q.removeFirst();val py=p/gw;val px=p%gw;area++;minGX=min(minGX,px);maxGX=max(maxGX,px);minGY=min(minGY,py);maxGY=max(maxGY,py)
                for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=px+dx;val ny=py+dy;if(nx !in 0 until gw||ny !in 0 until gh)continue;val ni=ny*gw+nx;if(seen[ni])continue;val xx=l+nx*step;val yy=t+ny*step;if(fg(xx,yy)){seen[ni]=true;q.add(ni)}}
            }
            if(area<18||area>1800)continue
            val box=BoundingBox(max(l,l+minGX*step-1),max(t,t+minGY*step-1),min(r,l+(maxGX+1)*step+1),min(b,t+(maxGY+1)*step+1))
            if(box.width !in 6..82||box.height !in 6..68)continue
            val dist=hypot(box.centerX-cx.toDouble(),box.centerY-cy.toDouble());if(dist>56||box.centerX>=cx-2)continue
            blobs+=Blob(box,area,dist,stats(bitmap,box))
        }
        val chosen=blobs.sortedWith(compareBy<Blob>{it.dist}.thenByDescending{it.area}).firstOrNull()?:return null
        val s=chosen.stats
        // RSS families: ore is strongly blue, stone is grey/rock-like, wood has green/brown foliage,
        // food has a yellow/orange signature. A red/orange-dominant, green-poor footprint is usually a
        // monster/combat object and is deliberately rejected.
        if(s.red>.30f&&s.orange>.28f&&s.green<.35f)return null
        val scores=mapOf(
            "Ore" to (s.blue*115f+s.gray*20f-s.red*35f),
            "Stone" to (s.gray*110f+s.blue*20f-s.orange*35f-s.red*25f),
            "Wood" to (s.green*100f+s.orange*35f+s.yellow*15f-s.red*25f-s.blue*15f),
            "Food" to (s.yellow*120f+s.orange*55f+s.green*20f-s.blue*25f-s.red*15f)
        ).toList().sortedByDescending{it.second}
        val best=scores[0];val second=scores[1]
        if(best.second<25f||best.second-second.second<7f)return null
        val conf=(55f+best.second*.34f+min(chosen.area/900f,.20f)*45f).toInt().coerceIn(0,94)
        return Artwork(best.first,conf,chosen.box,dominant(bitmap,chosen.box),chosen.area/900f,s.red,s.green,s.blue,s.gray,s.yellow,s.orange)
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
    private fun flagScore(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Int{val l=max(left,badge.minX-28);val r=min(right,badge.maxX+30);val t=max(top,badge.minY-42);val b=min(bottom,badge.minY+12);var red=0;var strong=0;var upper=0;var n=0;for(y in t..b step 2)for(x in l..r step 2){val c=bitmap.getPixel(x,y);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);n++;if(rr>155&&rr>gg*1.35f&&rr>bb*1.35f){red++;if(rr>205)strong++;if(y<badge.centerY)upper++}};if(n==0)return 0;var s=0;if(red>=5)s+=18;if(red>=12)s+=18;if(strong>=4)s+=12;if(upper>=4)s+=15;if(red>100)s-=20;return s.coerceIn(0,100)}
    private fun movingScore(bitmap:Bitmap,badge:BoundingBox,left:Int,top:Int,right:Int,bottom:Int):Int{val cx=badge.centerX;val cy=badge.centerY;val dirs=arrayOf(intArrayOf(1,0),intArrayOf(-1,0),intArrayOf(0,1),intArrayOf(0,-1),intArrayOf(1,1),intArrayOf(-1,1),intArrayOf(1,-1),intArrayOf(-1,-1));var best=0;for(d in dirs){var hits=0;var run=0;var maxRun=0;for(dist in 18..105 step 3){val x=cx+d[0]*dist;val y=cy+d[1]*dist;if(x !in left..right||y !in top..bottom)break;var hit=false;for(oy in -2..2)for(ox in -2..2){val xx=x+ox;val yy=y+oy;if(xx !in left..right||yy !in top..bottom)continue;val c=bitmap.getPixel(xx,yy);val rr=Color.red(c);val gg=Color.green(c);val bb=Color.blue(c);val mx=max(rr,max(gg,bb));val mn=min(rr,min(gg,bb));if(mx-mn>65&&mx>125)hit=true};if(hit){hits++;run++;maxRun=max(maxRun,run)}else run=0};best=max(best,(hits*5+maxRun*4).coerceAtMost(100))};return best}
    private fun blueRatio(bitmap:Bitmap,box:BoundingBox):Float{var q=0;var n=0;for(y in box.minY..box.maxY step 2)for(x in box.minX..box.maxX step 2){val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);n++;if(b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f)q++};return if(n==0)0f else q.toFloat()/n}
    private fun dedupe(input:List<RssDetection>):List<RssDetection>{val out=ArrayList<RssDetection>();for(d in input)if(out.none{abs(d.centerX-it.centerX)+abs(d.centerY-it.centerY)<24})out+=d;return out.sortedWith(compareByDescending<RssDetection>{it.confidence}.thenByDescending{it.level})}
}