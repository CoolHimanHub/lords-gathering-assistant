package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * V36 focused map-coordinate OCR.
 *
 * The viewport used by the scanner must come from the same captured frame as
 * the badge detections. This reader therefore performs focused OCR only on
 * that bitmap and accepts only an explicit, plausible X/Y pair. If it cannot
 * prove the pair, the caller must pause rather than fall back to unrelated
 * full-screen OCR.
 */
object ViewportOcrReader {
    data class Result(val x: Int, val y: Int)

    private const val MIN_COORD = 0
    private const val MAX_COORD = 9999
    private const val OCR_TIMEOUT_MS = 1200L

    fun read(bitmap: Bitmap, recognizer: TextRecognizer): Result? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 600 || h < 400) return null

        // Multiple normalized crops make the reader tolerant of the different
        // capture sizes/aspect ratios seen in the live-game recording and the
        // earlier 1536x707 screenshots.
        val crops = listOf(
            Crop((w * 0.40f).toInt(), (h * 0.035f).toInt(), (w * 0.70f).toInt(), (h * 0.21f).toInt()),
            Crop((w * 0.455f).toInt(), (h * 0.065f).toInt(), (w * 0.635f).toInt(), (h * 0.19f).toInt()),
            Crop((w * 0.32f).toInt(), (h * 0.015f).toInt(), (w * 0.80f).toInt(), (h * 0.25f).toInt()),
            Crop((w * 0.25f).toInt(), 0, (w * 0.86f).toInt(), (h * 0.30f).toInt())
        )

        for (definition in crops) {
            val crop = makeCrop(bitmap, definition) ?: continue
            try {
                for (variant in makeVariants(crop)) {
                    try {
                        val scaled = Bitmap.createScaledBitmap(
                            variant,
                            max(variant.width * 4, 1),
                            max(variant.height * 4, 1),
                            true
                        )
                        try {
                            val result = try {
                                Tasks.await(
                                    recognizer.process(InputImage.fromBitmap(scaled, 0)),
                                    OCR_TIMEOUT_MS,
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
                        if (variant !== crop) {
                            try { variant.recycle() } catch (_: Exception) {}
                        }
                    }
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

    private fun makeVariants(source: Bitmap): List<Bitmap> {
        val gray = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val highContrast = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        grayPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        contrastPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            2.2f, 0f, 0f, 0f, -170f,
            0f, 2.2f, 0f, 0f, -170f,
            0f, 0f, 2.2f, 0f, -170f,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(gray).drawBitmap(source, null, Rect(0, 0, source.width, source.height), grayPaint)
        Canvas(highContrast).drawBitmap(source, null, Rect(0, 0, source.width, source.height), contrastPaint)
        return listOf(source, gray, highContrast)
    }

    private fun parse(text: String): Result? {
        val compact = normalize(text)
        val patterns = listOf(
            Regex("\\bX\\s*[:=]\\s*(\\d{1,4})\\s*[^0-9A-Z]{1,8}\\s*Y\\s*[:=]\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*(\\d{1,4})\\s*[^0-9A-Z]{1,8}\\s*Y\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
            Regex("\\bX\\s*[:=]?\\s*(\\d{1,4})\\D{1,10}Y\\s*[:=]?\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val m = pattern.find(compact) ?: continue
            val x = m.groupValues[1].toIntOrNull() ?: continue
            val y = m.groupValues[2].toIntOrNull() ?: continue
            if (x in MIN_COORD..MAX_COORD && y in MIN_COORD..MAX_COORD) return Result(x, y)
        }
        return null
    }

    private fun normalize(text: String): String = text
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', 'I')
        .replace('—', '-')
        .replace('–', '-')
        .replace('：', ':')
        .replace('=', ':')
        .replace(Regex("(?i)\\bK\\s*[:;.]"), "X:")
        .replace(Regex("(?i)\\bX\\s*[;.]"), "X:")
        .replace(Regex("(?i)\\bY\\s*[;.]"), "Y:")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun repair(text: String): String = normalize(text)
        .replace(Regex("(?i)\\bX\\s*[:;.]?\\s*(?=\\d)"), "X:")
        .replace(Regex("(?i)\\bY\\s*[:;.]?\\s*(?=\\d)"), "Y:")
}
