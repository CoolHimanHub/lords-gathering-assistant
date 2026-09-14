package com.coolhimanhub.lordsgathering

import kotlin.math.abs

/** Fast tile semantics layer.
 * A tile is represented independently from its screen position so the grid
 * learner answers "where is the tile?" and this model answers "what is on it?".
 *
 * Runtime deliberately uses cheap evidence fusion rather than a heavyweight
 * neural network on every frame. The feature schema is ML-ready and can later
 * be trained from labelled screenshots without changing the TileObservation API.
 */
class TileSemanticModel {
    enum class Kind { RSS, CITY, CASTLE, TERRAIN, EMPTY, UNKNOWN }
    enum class Resource { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    enum class Occupancy { FREE, OCCUPIED, APPROACHING, UNKNOWN }

    data class TileObservation(
        val gameX:Int,
        val gameY:Int,
        val screenX:Int,
        val screenY:Int,
        val kind:Kind,
        val resource:Resource=Resource.UNKNOWN,
        val level:Int=0,
        val occupancy:Occupancy=Occupancy.UNKNOWN,
        val confidence:Int=0,
        val movingScore:Int=0
    )

    data class ClassifiedTile(
        val observation:TileObservation,
        val isRss:Boolean,
        val canGather:Boolean,
        val reason:String
    )

    fun classify(o:TileObservation):ClassifiedTile {
        if(o.kind!=Kind.RSS) return ClassifiedTile(o,false,false,"not_rss")
        if(o.confidence<70) return ClassifiedTile(o,true,false,"low_confidence")
        return when(o.occupancy){
            Occupancy.FREE -> ClassifiedTile(o,true,true,"free_rss")
            Occupancy.OCCUPIED -> ClassifiedTile(o,true,false,"occupied")
            Occupancy.APPROACHING -> ClassifiedTile(o,true,false,"player_approaching")
            Occupancy.UNKNOWN -> ClassifiedTile(o,true,false,"occupancy_unknown")
        }
    }

    /** Normalized feature vector for offline training/export. */
    fun features(o:TileObservation):FloatArray = floatArrayOf(
        o.gameX/1000f, o.gameY/1000f,
        o.screenX/2000f, o.screenY/1000f,
        o.kind.ordinal/4f, o.resource.ordinal/5f,
        o.level/10f, o.occupancy.ordinal/3f,
        o.confidence/100f, o.movingScore/100f
    )
}
