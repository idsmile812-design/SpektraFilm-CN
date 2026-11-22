/*
 * Spektrafilm for Android — workbench finishing (border + date stamp). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * This is a POST-engine, app-side print finish. It must never reach the film engine: the frame
 * and date are composed onto the already graded Bitmap with Android Canvas, after the engine and
 * ColorGrade have run, so automatic exposure and the film simulation see exactly the unframed
 * image. The geometry/date decisions are pure JVM code in [WorkbenchFinishingGeometry] so they
 * can be unit-tested without a device; only [WorkbenchFinishing.render] touches Bitmap/Canvas.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class WorkbenchBorder { NONE, WHITE, BLACK }
internal enum class WorkbenchDate { OFF, DATE, DATE_TIME }

internal const val WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS = 253_402_300_799_999L

/**
 * The pending finishing recipe for the next photo. It is part of [WorkbenchSelection] so the
 * workbench state survives process death, but it is deliberately NOT part of SpektraParams: the
 * engine never sees a border or a date.
 */
internal data class WorkbenchFinishingConfig(
    val border: WorkbenchBorder = WorkbenchBorder.NONE,
    val date: WorkbenchDate = WorkbenchDate.OFF,
    val captureTimestampMs: Long = 0L,
) {
    val isIdentity: Boolean
        get() = border == WorkbenchBorder.NONE && date == WorkbenchDate.OFF

    fun normalizedCaptureTimestampMs(): Long =
        captureTimestampMs.coerceIn(0L, WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS)

    /**
     * The cache-key contribution. NONE+OFF is deliberately the empty string, so an untouched
     * workbench keeps the historical (pre-finishing) cache identity and a legacy caller that
     * omits the key behaves exactly as it did. Any decoration or capture time joins the key.
     */
    fun cacheKey(): String = if (isIdentity) {
        ""
    } else {
        "border=${border.name};date=${date.name};ts=${normalizedCaptureTimestampMs()}"
    }
}

/**
 * Result of the pure geometry pass. Colours are ARGB longs rather than android.graphics.Color so
 * the whole calculation runs on the JVM test classpath.
 */
internal data class WorkbenchFinishingLayout(
    val outputWidth: Int,
    val outputHeight: Int,
    val imageLeft: Int,
    val imageTop: Int,
    val hasFrame: Boolean,
    val frameColor: Long,
    val background: Long,
    val dateText: String?,
    val dateRight: Float,
    val dateBaseline: Float,
    val dateTextSizePx: Float,
    val dateTextColor: Long,
    val accentColor: Long,
)

internal object WorkbenchFinishingGeometry {
    const val BLACK = 0xFF000000L
    const val WHITE = 0xFFFFFFFFL
    private const val DATE_ON_LIGHT = 0xFF141414L
    private const val DATE_ON_DARK = 0xFFE7EEE9L
    private const val ACCENT_ON_DARK = 0xFFE8A44FL
    private const val ACCENT_ON_LIGHT = 0xFF4A3A24L

    private const val DATE_PATTERN = "yyyy.MM.dd"
    private const val DATE_TIME_PATTERN = "yyyy.MM.dd  HH:mm"

    /** Frame band as a fraction of the short edge, clamped to a sane physical thickness. */
    private const val FRAME_FRACTION = 0.030f
    private const val DATE_TEXT_FRACTION = 0.030f
    private const val DATE_PADDING_FRACTION = 0.020f

    fun dateText(
        date: WorkbenchDate,
        captureTimestampMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String? {
        if (date == WorkbenchDate.OFF) return null
        val pattern = if (date == WorkbenchDate.DATE_TIME) DATE_TIME_PATTERN else DATE_PATTERN
        val format = SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = timeZone }
        val safe = captureTimestampMs.coerceIn(0L, WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS)
        return format.format(Date(safe))
    }

    fun layout(
        width: Int,
        height: Int,
        config: WorkbenchFinishingConfig,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): WorkbenchFinishingLayout {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val normalized = config
        val shortEdge = min(w, h).toFloat()
        val framed = normalized.border != WorkbenchBorder.NONE
        val frame = if (framed) {
            (shortEdge * FRAME_FRACTION).roundToInt().coerceAtLeast(2)
        } else {
            0
        }
        val text = dateText(normalized.date, normalized.captureTimestampMs, timeZone)
        val textSize = (shortEdge * DATE_TEXT_FRACTION).coerceIn(10f, 96f)
        val padding = (shortEdge * DATE_PADDING_FRACTION).roundToInt().coerceAtLeast(4)

        val left = frame
        val top = frame
        val right = frame
        val dateBand = if (text != null) {
            (textSize * 1.7f + padding * 2f).roundToInt().coerceAtLeast(1)
        } else {
            0
        }
        val bottom = frame + dateBand
        val outputWidth = w + left + right
        val outputHeight = h + top + bottom

        val background = when (normalized.border) {
            WorkbenchBorder.WHITE -> WHITE
            WorkbenchBorder.BLACK -> BLACK
            // A borderless date needs its own print strip below the photo, never a wash over it.
            WorkbenchBorder.NONE -> BLACK
        }
        val textColor = when (normalized.border) {
            WorkbenchBorder.WHITE -> DATE_ON_LIGHT
            WorkbenchBorder.NONE, WorkbenchBorder.BLACK -> DATE_ON_DARK
        }
        val accent = when (normalized.border) {
            WorkbenchBorder.WHITE -> ACCENT_ON_LIGHT
            WorkbenchBorder.NONE, WorkbenchBorder.BLACK -> ACCENT_ON_DARK
        }
        val baseline = if (text != null) {
            top + h + (dateBand + textSize * 0.72f) / 2f
        } else {
            0f
        }
        val dateRight = (outputWidth - right - padding).toFloat()
        return WorkbenchFinishingLayout(
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            imageLeft = left,
            imageTop = top,
            hasFrame = framed,
            frameColor = background,
            background = background,
            dateText = text,
            dateRight = dateRight,
            dateBaseline = baseline,
            dateTextSizePx = textSize,
            dateTextColor = textColor,
            accentColor = accent,
        )
    }
}

internal object WorkbenchFinishing {

    /**
     * Composite the print border/date onto [source]. Returns [source] itself for NONE+OFF (no
     * copy). Any other configuration returns a NEW bitmap; the caller owns it and the source is
     * never recycled here.
     */
    fun render(source: Bitmap, config: WorkbenchFinishingConfig): Bitmap {
        if (config.isIdentity || source.isRecycled) return source
        val layout = WorkbenchFinishingGeometry.layout(source.width, source.height, config)
        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.createBitmap(
                layout.outputWidth,
                layout.outputHeight,
                Bitmap.Config.ARGB_8888,
                false,
                source.colorSpace ?: android.graphics.ColorSpace.get(
                    android.graphics.ColorSpace.Named.SRGB,
                ),
            )
        } else {
            Bitmap.createBitmap(layout.outputWidth, layout.outputHeight, Bitmap.Config.ARGB_8888)
        }
        try {
            val canvas = Canvas(output)
            canvas.drawColor(layout.background.toInt())
            canvas.drawBitmap(source, layout.imageLeft.toFloat(), layout.imageTop.toFloat(), null)
            val text = layout.dateText
            if (text != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layout.dateTextColor.toInt()
                    textSize = layout.dateTextSizePx
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    textAlign = Paint.Align.LEFT
                }
                val width = paint.measureText(text)
                val textLeft = layout.dateRight - width
                // A small engraved index mark, not a caption: one solid square at the left of the
                // stamp keeps the strip identifiable without a logo or a second typeface.
                val accent = max(3f, layout.dateTextSizePx * 0.22f)
                val accentTop = layout.dateBaseline - accent
                canvas.drawRect(
                    textLeft - accent - layout.dateTextSizePx * 0.35f,
                    accentTop,
                    textLeft - layout.dateTextSizePx * 0.35f,
                    layout.dateBaseline,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layout.accentColor.toInt() },
                )
                canvas.drawText(text, textLeft, layout.dateBaseline, paint)
            }
            return output
        } catch (failure: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw failure
        }
    }
}
