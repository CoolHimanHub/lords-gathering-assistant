package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * V35 focused map-coordinate OCR.
 *
 * The game renders X/Y in a small HUD near the upper-middle of the map. V34
 * used broad crops, which allowed unrelated numbers and map text to compete
 * with the coordinate pair. V35 uses a tight crop first, then progressively
 * wider fallbacks. Each crop is tested in several visual variants and only an
 * explicit, plausible X/Y pair is accepted.
 *
 * A failed read is a safe failure: the caller must pause coverage rather than
 * guessing a viewport from pixels or stale OCR.
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

        // Calibrated from the supplied 1536x707 recordings. The first crop is
        // intentionally tight around the visible "X:### Y:###" HUD. Wider
        // crops are fallbacks for devices/scales where the HUD moves slightly.
        val crops = listOf(
            Crop((w * 0.455f).toInt(), (h * 0.065f).toInt(), (w * 0.635f).toInt(), (h * 0.19f).toInt()),
            Crop((w * 0.40f).toInt(), (h * 0.035f).toInt(), (w * 0.70f).toInt(), (h * 0.21f).toInt()),
            Crop((w * 0.32f).toInt(), (h * 0.015f).toInt(), (w * 0.80f).toInt(), (h * 0.25f).toInt()),
            // Last fallback: the whole top HUD strip, still excluding most of
            // the map and the bottom fixed controls.
            Crop((w * 0.25f).toInt(), 0, (w * 0.86f).toInt(), (h * 0.30f).toInt())
        )

        for (definition in crops) {
            val crop = makeCrop(bitmap, definition) ?: continue
            try {
                val variants = makeVariants(crop)
                for (variant in variants) {
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

    /**
     * Keep the original RGB crop plus two cheap preprocessing variants. The
     * coordinate text is bright over a dark/translucent HUD, so grayscale and
     * high-contrast variants often succeed when the raw crop does not.
     */
    private fun makeVariants(source: Bitmap): List<Bitmap> {
        val gray = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val highContrast = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvasGray = Canvas(gray)
        val canvasContrast = Canvas(highContrast)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
            2.2f, 0f, 0f, 0f, -170f,
            0f, 2.2f, 0f, 0f, -170f,
            0f, 0f, 2.2f, 0f, -170f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        contrastPaint.colorFilter = android.graphics.ColorMatrixColorFilter(contrastMatrix)
        canvasGray.drawBitmap(source, null, Rect(0, 0, source.width, source.height), paint)
        canvasContrast.drawBitmap(source, null, Rect(0, 0, source.width, source.height), contrastPaint)
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

    /**
     * ML Kit commonly confuses the HUD glyphs X/Y and punctuation. Normalize
     * only characters that are plausible OCR substitutions; never manufacture
     * missing digits.
     */
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

    private fun repair(text: String): String {
        val normalized = normalize(text)
        // OCR sometimes inserts a space between a label and punctuation.
        return normalized
            .replace(Regex("(?i)\\bX\\s*[:;.]?\\s*(?=\\d)"), "X:")
            .replace(Regex("(?i)\\bY\\s*[:;.]?\\s*(?=\\d)"), "Y:")
    }
}
