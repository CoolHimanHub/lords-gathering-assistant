package com.coolhimanhub.lordsgatheringassistant

/** V57.6 bridge: normalize screen detections into stable map-grid cells. */
class GridDetectionPipeline(private val grid: TileGridRecognizer = TileGridRecognizer()) {
    data class Candidate(val cell: TileGridRecognizer.Cell, val detection: ScreenAnalyzer.RssDetection)

    fun normalize(detections: List<ScreenAnalyzer.RssDetection>): List<Candidate> {
        val result = LinkedHashMap<Pair<Int,Int>, Candidate>()
        for (d in detections) {
            if (d.type.equals("unknown", true)) continue
            val c = grid.cell(d.centerX, d.centerY)
            val key = c.gx to c.gy
            val old = result[key]
            if (old == null || d.confidence > old.detection.confidence) {
                result[key] = Candidate(c, d)
            }
        }
        return result.values.toList()
    }
}
