package com.coolhimanhub.lordsgatheringassistant

/**
 * V56.2 bridge between the proven ScreenAnalyzer/coordinate mapper and the
 * semantic tile layer. It intentionally performs no taps. Its only job is to
 * convert a detector result into an exact tile observation and apply the
 * semantic gate before a candidate can reach the existing gather controller.
 */
class TileGridSemanticBridge(
    private val mapper:GameCoordinateMapper,
    private val semantics:TileSemanticModel=TileSemanticModel()
) {
    data class Result(
        val tile:TileSemanticModel.ClassifiedTile,
        val residual:Float,
        val gridLocked:Boolean
    )

    fun evaluate(
        detection:ScreenAnalyzer.RssDetection,
        viewport:MapViewportTracker.Viewport,
        width:Int,
        height:Int
    ):Result {
        val game=mapper.map(detection.centerX,detection.centerY,viewport.x,viewport.y,width,height)
        val calibration=mapper.calibration()
        val residual=mapper.tileResidual(detection.centerX,detection.centerY,game,viewport.x,viewport.y,width,height)
        val occupancy=when {
            detection.moving -> TileSemanticModel.Occupancy.APPROACHING
            detection.occupied -> TileSemanticModel.Occupancy.OCCUPIED
            else -> TileSemanticModel.Occupancy.FREE
        }
        val observation=TileSemanticModel.TileObservation(
            gameX=game.x,
            gameY=game.y,
            screenX=detection.centerX,
            screenY=detection.centerY,
            kind=TileSemanticModel.Kind.RSS,
            resource=resourceOf(detection.type),
            level=detection.level,
            occupancy=occupancy,
            confidence=detection.confidence,
            movingScore=detection.movingScore
        )
        return Result(semantics.classify(observation),residual,calibration.ready)
    }

    private fun resourceOf(type:String):TileSemanticModel.Resource = when(type.lowercase()) {
        "food","farm","grassland" -> TileSemanticModel.Resource.FOOD
        "timber","wood","woods" -> TileSemanticModel.Resource.TIMBER
        "stone","rocks","rock" -> TileSemanticModel.Resource.STONE
        "ore","rich vein","rich_vein" -> TileSemanticModel.Resource.ORE
        "gold","ruins","ruin" -> TileSemanticModel.Resource.GOLD
        else -> TileSemanticModel.Resource.UNKNOWN
    }
}
