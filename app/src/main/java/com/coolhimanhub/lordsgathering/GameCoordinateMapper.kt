package com.coolhimanhub.lordsgatheringassistant

import kotlin.math.roundToInt

/**
 * Converts the detector's screen-pixel location into Lords Mobile map X/Y.
 *
 * The map is rendered as an isometric grid. The viewport OCR gives the game
 * coordinate at the map centre; the detector gives the resource's screen
 * position.  At the V37 reference resolution (1536x707), one game-coordinate
 * step is calibrated as a 64x32 pixel diamond. The calibration scales with
 * the actual screenshot resolution.
 *
 * This conversion is only used for display/deduplication. Taps continue to
 * use the original screen coordinates, so coordinate presentation cannot
 * change the physical tap target.
 */
class GameCoordinateMapper {
    data class GameLocation(val x:Int,val y:Int)

    companion object {
        private const val REF_W = 1536f
        private const val REF_H = 707f
        private const val REF_HALF_TILE_W = 32f
        private const val REF_HALF_TILE_H = 16f

        fun map(screenX:Int,screenY:Int,viewportX:Int,viewportY:Int,
                screenWidth:Int,screenHeight:Int):GameLocation {
            val sx=screenWidth/REF_W
            val sy=screenHeight/REF_H
            val centreX=screenWidth/2f
            val centreY=screenHeight/2f

            val halfW=(REF_HALF_TILE_W*sx).coerceAtLeast(1f)
            val halfH=(REF_HALF_TILE_H*sy).coerceAtLeast(1f)
            val dx=screenX-centreX
            val dy=screenY-centreY

            // Isometric projection:
            // screenDx = halfW*(worldDx-worldDy)
            // screenDy = halfH*(worldDx+worldDy)
            val worldDx=(dx/halfW + dy/halfH)/2f
            val worldDy=(dy/halfH - dx/halfW)/2f

            return GameLocation(
                (viewportX+worldDx).roundToInt().coerceIn(0,9999),
                (viewportY+worldDy).roundToInt().coerceIn(0,9999)
            )
        }
    }
}
