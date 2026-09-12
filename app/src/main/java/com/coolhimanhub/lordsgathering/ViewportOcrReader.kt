package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * V34 focused map-coordinate OCR.
 *
 * The coordinate HUD is a small, fixed region near the upper-middle of the
 * gameplay screen. OCR'ing the complete 1536x707 frame lets unrelated numbers
 * compete with X/Y. This reader instead uses several overlapping crops,
 * upscales them, and accepts only an explicit X/Y pair.
 */
object ViewportOcrReader {
    data class Result(val x: Int, val y: Int)

    fun read(bitmap: Bitmap, recognizer: TextRecognizer): Result? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 600 || h < 400) return null

        val crops = listOf(
            Crop((w * 0.42f).toInt(), (h * 0.035f).toInt(), (w * 0.70f).toInt(), (h * 0.18f).toInt()),
            Crop((w * 0.34f).toInt(), (h * 0.02f).toInt(), (w * 0.78f).toInt(), (h * 0.23f).toInt()),
            Crop((w * 0.38f).toInt(), (h * 0.07f).toInt(), (w * 0.74f).toInt(), (h * 0.27f).toInt())
        )

        for (definition in crops) {
            val crop = makeCrop(bitmap, definition) ?: continue
            try {
                val scaled = Bitmap.createScaledBitmap(
                    crop,
                    max(crop.width * 4, 1),
                    max(crop.height * 4, 1),
                    true
                )
                try {
                    val result = try {
                        Tasks.await(
                            recognizer.process(InputImage.fromBitmap(scaled, 0)),
                            1800L,
                            TimeUnit.MILLISECONDS
                        )
                    } catch (_: Exception) {
                        null
                    }
                    if (result != null) {
                        parse(result.text)?.let { return it }
                        parse(repair(result.text))?.let { return it }
                    }
                } finally {
                    try { scaled.recycle() } catch (_: Exception) {}
                }
            } finally {
                try { crop.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }

    private data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun makeCrop(bitmap: Bitmap, c: Crop): Bitmap? {
        val left = c.left.coerceIn(0, bitmap.width - 1)
        val top = c.top.coerceIn(0, bitmap.height - 1)
        val right = c.right.coerceIn(left + 1, bitmap.width)
        val bottom = c.bottom.coerceIn(top + 1, bitmap.height)
        if (right - left < 20 || bottom - top < 10) return null
        return try { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) } catch (_: Exception) { null }
    }

    private fun parse(text: String): Result? {
        val compact = text.replace('\n', ' ').replace('|', 'I')
        val patterns = listOf(
            Regex("X\\s*[:=]\\s*(\\d{1,4})\\D+Y\\s*[:=]\\s*(\\d{1,4})", RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*(\\d{1,4})\\D+Y\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val m = pattern.find(compact) ?: continue
            return Result(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return null
    }

    private fun repair(text: String): String = text
        .replace('\n', ' ')
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("(?i)\\bK\\s*[:=]"), "X:")
        .replace(Regex("(?i)\\bX\\s*[;.]"), "X:")
        .replace(Regex("(?i)\\bY\\s*[;.]"), "Y:")
}
