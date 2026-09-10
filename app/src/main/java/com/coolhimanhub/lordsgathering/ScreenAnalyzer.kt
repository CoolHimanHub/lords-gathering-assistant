package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

/**
 * V25 RSS detector.
 *
 * Derived from the supplied gameplay videos: RSS artwork is a cluster of
 * separate sprite pieces with a small blue level badge offset to the right.
 * Monsters/terrain can also contain blue UI elements, so the detector uses
 * badge + local artwork geometry as the candidate gate. Occupancy and motion
 * are NOT inferred from one frame; those must be verified temporally / from
 * the opened tile panel before an action is allowed.
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
            // A single screenshot cannot reliably prove movement or occupancy.
            // Those states are deliberately left for the open-tile verification phase.
            val confidence=artwork.confidence
            if(confidence<MIN_CONFIDENCE)continue
            result+=RssDetection(
                artwork.type,badge.digit,artwork.box.centerX,artwork.box.centerY,
                artwork.box,confidence,false,artwork.dominant,false,0
            )
        }
        return dedupe(result)
    }

    private fun findBadges(bitmap:Bitmap,left:Int,right:Int,top:Int,bottom:Int):List<Badge>{
        // The earlier blue-component detector lost badges when the badge touched
        // blue/ore pixels. Instead, locate compact white glyphs surrounded by a
        // blue badge neighbourhood. This is much closer to the badge geometry
        // visible in the supplied videos.
        val step=2
        val gw=(right-left)/step+1
        val gh=(bottom-top)/step+1
        val visited=BooleanArray(gw*gh)
        val queue=ArrayDeque<Int>()
        val found=ArrayList<Badge>()
        fun whiteAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            return r>=145&&g>=145&&b>=145&&max(r,max(g,b))-min(r,min(g,b))<115
        }
        fun blueAt(x:Int,y:Int):Boolean{
            val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            return b>=82&&b-r>=16&&b>=g*.94f&&b>=r*1.08f
        }
        for(gy in 0 until gh)for(gx in 0 until gw){
            val start=gy*gw+gx;if(visited[start])continue
            val sx=min(right,left+gx*step);val sy=min(bottom,top+gy*step)
            if(!whiteAt(sx,sy)){visited[start]=true;continue}
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
                    if(whiteAt(xx,yy)){visited[ni]=true;queue.add(ni)}
                }
            }
            if(count !in 8..220)continue
            val glyph=BoundingBox(max(left,left+minGX*step-1),max(top,top+minGY*step-1),min(right,left+(maxGX+1)*step+1),min(bottom,top+(maxGY+1)*step+1))
            if(glyph.width !in 3..24||glyph.height !in 7..30)continue
            val padX=max(6,glyph.width/2+2);val padY=max(5,glyph.height/2+2)
            val ring=BoundingBox(max(left,glyph.minX-padX),max(top,glyph.minY-padY),min(right,glyph.maxX+padX),min(bottom,glyph.maxY+padY))
            val ratio=blueRatio(bitmap,ring)
            if(ratio<.12f)continue
            val digit=readBadgeDigit(bitmap,glyph)?:continue
            val badge=BoundingBox(max(left,glyph.minX-4),max(top,glyph.minY-4),min(right,glyph.maxX+4),min(bottom,glyph.maxY+4))
            found+=Badge(badge,ratio,digit)
        }
        return found.sortedWith(compareBy({it.box.centerY},{it.box.centerX})).fold(ArrayList()){acc,b->if(acc.none{abs(it.box.centerX-b.box.centerX)+abs(it.box.centerY-b.box.centerY)<18})acc.add(b);acc}
    }

    private fun readBadgeDigit(bitmap:Bitmap,glyph:BoundingBox):Int?{
        val w=glyph.width;val h=glyph.height
        if(w<3||h<7)return null
        val grid=Array(7){BooleanArray(5)}
        for(gy in 0 until 7)for(gx in 0 until 5){
            val xa=glyph.minX+gx*w/5;val xb=max(xa+1,glyph.minX+(gx+1)*w/5)
            val ya=glyph.minY+gy*h/7;val yb=max(ya+1,glyph.minY+(gy+1)*h/7)
            var on=0;var total=0
            for(y in ya until min(glyph.maxY+1,yb))for(x in xa until min(glyph.maxX+1,xb)){
                val c=bitmap.getPixel(x,y);val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c);total++
                if(r>=145&&g>=145&&b>=145&&max(r,max(g,b))-min(r,min(g,b))<115)on++
            }
            grid[gy][gx]=total>0&&on*100>=total*12
        }
        fun row(y:Int)=grid[y].count{it}/5f
        fun col(x:Int)=(0 until 7).count{grid[it][x]}/7f
        val top=(row(0)+row(1))/2f;val mid=(row(3)+row(4))/2f;val bot=(row(5)+row(6))/2f;val lu=(col(0)+col(1))/2f;val ru=(col(3)+col(4))/2f
        val scores=listOf(
            2 to top*26+mid*28+bot*30+ru*16+lu*17-lu*8-ru*6,
            3 to top*25+mid*30+bot*27+ru*20+lu*10-lu*12,
            4 to mid*34+ru*32+lu*17+ru*12-top*12-bot*14,
            5 to top*29+mid*29+bot*29+lu*20+ru*15-ru*8
        ).sortedByDescending{it.second}
        if(scores[0].second<10f||scores[0].second-scores[1].second<1f)return null
        return scores[0].first
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
        // The videos show monsters with strong red/purple bodies and a different
        // compactness pattern. Reject only the clearly red/orange-dominant case;
        // do not use a one-frame "movement" guess.
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
