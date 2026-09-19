package com.coolhiman.lordsassistant.data

import android.content.Context
import android.graphics.Bitmap
import com.coolhiman.lordsassistant.model.TrainingSample
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DatasetStore(private val context: Context) {
    private val root = File(context.filesDir, "calibration_dataset").apply { mkdirs() }
    private val imageDir = File(root, "images").apply { mkdirs() }
    private val metadataFile = File(root, "samples.jsonl")

    fun saveCrop(
        source: Bitmap,
        labelType: String,
        label: String,
        level: Int?,
        kingdom: Int?,
        worldX: Int?,
        worldY: Int?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): TrainingSample {
        require(right > left && bottom > top)
        val id = UUID.randomUUID().toString()
        val imageFile = File(imageDir, "$id.png")
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        imageFile.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        val sample = TrainingSample(
            id, labelType, label, level, kingdom, worldX, worldY,
            imageFile.relativeTo(root).path, left, top, right, bottom
        )
        val o = JSONObject().apply {
            put("id", sample.id)
            put("labelType", sample.labelType)
            put("label", sample.label)
            if (sample.level != null) put("level", sample.level)
            if (sample.kingdom != null) put("kingdom", sample.kingdom)
            if (sample.worldX != null) put("worldX", sample.worldX)
            if (sample.worldY != null) put("worldY", sample.worldY)
            put("imagePath", sample.imagePath)
            put("left", sample.left)
            put("top", sample.top)
            put("right", sample.right)
            put("bottom", sample.bottom)
            put("createdAtMs", sample.createdAtMs)
        }
        metadataFile.appendText(o.toString() + "\n")
        return sample
    }

    fun sampleCount(): Int =
        if (!metadataFile.exists()) 0 else metadataFile.useLines { it.count { line -> line.isNotBlank() } }

    fun listSamples(labelType: String? = null, label: String? = null): List<TrainingSample> {
        if (!metadataFile.exists()) return emptyList()
        return metadataFile.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val o = JSONObject(line)
                    val sample = TrainingSample(
                        id = o.getString("id"),
                        labelType = o.getString("labelType"),
                        label = o.getString("label"),
                        level = o.optInt("level").takeIf { o.has("level") },
                        kingdom = o.optInt("kingdom").takeIf { o.has("kingdom") },
                        worldX = o.optInt("worldX").takeIf { o.has("worldX") },
                        worldY = o.optInt("worldY").takeIf { o.has("worldY") },
                        imagePath = o.getString("imagePath"),
                        left = o.getInt("left"),
                        top = o.getInt("top"),
                        right = o.getInt("right"),
                        bottom = o.getInt("bottom"),
                        createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
                    )
                    if ((labelType == null || sample.labelType == labelType) &&
                        (label == null || sample.label == label)) sample else null
                }.getOrNull()
            }.toList()
        }
    }

    fun imageFile(sample: TrainingSample): File = File(root, sample.imagePath)

    fun datasetDirectory(): File = root
}
