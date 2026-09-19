package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.coolhiman.lordsassistant.data.DatasetStore
import com.coolhiman.lordsassistant.model.TrainingSample

/**
 * Loads locally collected RESOURCE/MONSTER crops as detector templates.
 * The caller owns the returned bitmaps and should recycle them after use.
 */
class TemplateLibrary(private val datasetStore: DatasetStore) {
    fun loadTileTemplates(): List<Pair<TileTemplate, Bitmap>> {
        return datasetStore.listSamples(labelType = "RESOURCE")
            .mapNotNull { load(it, TileClass.RESOURCE) } +
            datasetStore.listSamples(labelType = "MONSTER")
                .mapNotNull { load(it, TileClass.MONSTER) }
    }

    private fun load(sample: TrainingSample, tileClass: TileClass): Pair<TileTemplate, Bitmap>? {
        val bitmap = BitmapFactory.decodeFile(datasetStore.imageFile(sample).absolutePath) ?: return null
        return TileTemplate(sample.label, tileClass, sample.level, sample.imagePath) to bitmap
    }
}
