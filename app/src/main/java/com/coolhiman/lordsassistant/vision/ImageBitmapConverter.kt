package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.Image

object ImageBitmapConverter {
    fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        if (padded.width == image.width) return padded

        val cropped = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(
            padded,
            Rect(0, 0, image.width, image.height),
            Rect(0, 0, image.width, image.height),
            null
        )
        padded.recycle()
        return cropped
    }
}
