package com.coolhimanhub.lordsgatheringassistant

/** Fast tile semantics layer. Grid answers WHERE; this layer answers WHAT. */
class TileSemanticModel {
    enum class Kind { RSS, CITY, CASTLE, TERRAIN, EMPTY, UNKNOWN }
    enum class Resource { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    enum class Occupancy { FREE, OCCUPIED, APPROACHING, UNKNOWN }
    data class TileObservation(val gameX:Int,val gameY:Int,val screenX:Int,val screenY:Int,val kind:Kind,val resource:Resource=Resource.UNKNOWN,val level:Int=0,val occupancy:Occupancy=Occupancy.UNKNOWN,val confidence:Int=0,val movingScore:Int=0)
    data class ClassifiedTile(val observation:TileObservation,val isRss:Boolean,val canGather:Boolean,val reason:String)
    fun classify(o:TileObservation):ClassifiedTile {
        if(o.kind!=Kind.RSS)return ClassifiedTile(o,false,false,"not_rss")
        if(o.confidence<70)return ClassifiedTile(o,true,false,"low_confidence")
        return when(o.occupancy){Occupancy.FREE->ClassifiedTile(o,true,true,"free_rss");Occupancy.OCCUPIED->ClassifiedTile(o,true,false,"occupied");Occupancy.APPROACHING->ClassifiedTile(o,true,false,"player_approaching");Occupancy.UNKNOWN->ClassifiedTile(o,true,false,"occupancy_unknown")}
    }
    fun features(o:TileObservation)=floatArrayOf(o.gameX/1000f,o.gameY/1000f,o.screenX/2000f,o.screenY/1000f,o.kind.ordinal/5f,o.resource.ordinal/6f,o.level/10f,o.occupancy.ordinal/4f,o.confidence/100f,o.movingScore/100f)
}
