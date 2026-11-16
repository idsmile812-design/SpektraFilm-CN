/*
 * Spektrafilm for Android — pre-publication visual verification. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class ExportVisualCheckException(message: String) : IOException(message)

internal object ExportVisualCheck {
    const val MIN_CORRELATION = 0.55
    private const val GRID_EDGE = 48
    private const val MIN_GRID_EDGE = 8
    private const val DECODE_EDGE = 512
    private const val ASPECT_TOLERANCE = 0.005

    fun <T> withOneRetry(encode: () -> T, verify: (T) -> Unit, onRetry: () -> Unit = {}): T {
        for (attempt in 0..1) {
            val candidate = encode()
            try {
                verify(candidate)
                return candidate
            } catch (failure: ExportVisualCheckException) {
                if (attempt == 1) throw failure
                onRetry()
            }
        }
        error("unreachable")
    }

    fun correlation(first: DoubleArray, second: DoubleArray): Double? {
        if (first.size != second.size || first.isEmpty()) return null
        var firstMean = 0.0
        var secondMean = 0.0
        for (index in first.indices) {
            firstMean += first[index]
            secondMean += second[index]
        }
        firstMean /= first.size
        secondMean /= second.size
        var covariance = 0.0
        var firstVariance = 0.0
        var secondVariance = 0.0
        for (index in first.indices) {
            val a = first[index] - firstMean
            val b = second[index] - secondMean
            covariance += a * b
            firstVariance += a * a
            secondVariance += b * b
        }
        if (firstVariance <= 1e-9 || secondVariance <= 1e-9) return null
        val value = covariance / sqrt(firstVariance * secondVariance)
        return value.takeIf { it.isFinite() }
    }

    fun verify(file: File, expected: Bitmap) {
        if (expected.isRecycled || expected.width <= 0 || expected.height <= 0) {
            throw ExportVisualCheckException("Rendered bitmap is unavailable for visual verification")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ExportVisualCheckException("Encoded image could not be decoded")
        }
        val expectedAspect = expected.width.toDouble() / expected.height
        val encodedAspect = bounds.outWidth.toDouble() / bounds.outHeight
        if (kotlin.math.abs(expectedAspect - encodedAspect) > ASPECT_TOLERANCE * expectedAspect) {
            throw ExportVisualCheckException("Encoded image aspect ratio changed during export")
        }

        val gridWidth: Int
        val gridHeight: Int
        if (expected.width >= expected.height) {
            gridWidth = GRID_EDGE
            gridHeight = (GRID_EDGE * expected.height.toDouble() / expected.width)
                .roundToInt().coerceIn(MIN_GRID_EDGE, GRID_EDGE)
        } else {
            gridHeight = GRID_EDGE
            gridWidth = (GRID_EDGE * expected.width.toDouble() / expected.height)
                .roundToInt().coerceIn(MIN_GRID_EDGE, GRID_EDGE)
        }
        val expectedSamples = expected.lumaGrid(gridWidth, gridHeight)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > DECODE_EDGE) sample *= 2
            inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw ExportVisualCheckException("Encoded image could not be decoded")
        try {
            val actualSamples = decoded.lumaGrid(gridWidth, gridHeight)
            val correlation = correlation(expectedSamples, actualSamples) ?: return
            if (correlation < MIN_CORRELATION) {
                throw ExportVisualCheckException(
                    "Encoded image does not match the rendered bitmap (correlation=$correlation)",
                )
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun Bitmap.lumaGrid(width: Int, height: Int): DoubleArray {
        val scaled = if (this.width == width && this.height == height) {
            this
        } else {
            Bitmap.createScaledBitmap(this, width, height, true)
        }
        try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            return DoubleArray(pixels.size) { index ->
                val pixel = pixels[index]
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                0.2126 * red + 0.7152 * green + 0.0722 * blue
            }
        } finally {
            if (scaled !== this) scaled.recycle()
        }
    }
}
