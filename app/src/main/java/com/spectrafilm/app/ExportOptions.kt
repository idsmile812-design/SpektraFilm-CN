/*
 * Spektrafilm for Android — export options model (Lightroom-style export sheet §6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Lightroom's export is a format-aware settings sheet (format → format-specific options → size →
 * naming → metadata) rather than a single global setting. This is the pure, JVM-testable core of
 * our version: the size/quality/name choices and the maths behind them. The Compose sheet binds to
 * it; the export pipeline reads it. UI/post-engine only — no engine params, no parity impact.
 */
package com.spectrafilm.app

import androidx.annotation.StringRes
import com.spectrafilm.engine.ColorSpace
import kotlin.math.roundToInt

/** Localised label for an engine [ColorSpace] in pickers (the enum's `name` stays the internal id). */
@StringRes
internal fun ColorSpace.uiLabelRes(): Int = when (this) {
    ColorSpace.SRGB -> R.string.tool_color_space_srgb
    ColorSpace.ADOBE_RGB -> R.string.tool_color_space_adobe_rgb
    ColorSpace.PROPHOTO -> R.string.tool_color_space_prophoto
    ColorSpace.REC2020 -> R.string.tool_color_space_rec2020
    ColorSpace.ACES2065_1 -> R.string.tool_color_space_aces2065_1
    ColorSpace.LINEAR_SRGB -> R.string.tool_color_space_linear_srgb
}

/** Output-size choice in the export sheet (a post-render downscale, like Lightroom's "Dimensions"). */
enum class ExportSize(@StringRes val labelRes: Int, val longEdge: Int?) {
    FULL(R.string.tool_export_size_full, null),
    LARGE(R.string.tool_export_size_large, 4096),
    MEDIUM(R.string.tool_export_size_medium, 2048),
    SMALL(R.string.tool_export_size_small, 1024),
    CUSTOM(R.string.tool_export_size_custom, null),
}

/** The user's choices in the export sheet. Pure data; the UI binds to it and the pipeline reads it. */
data class ExportOptions(
    val format: ExportFormat,
    val jpegQuality: Int,
    val size: ExportSize,
    val customLongEdge: Int,
    val customName: String,
) {
    /**
     * Target long edge in px for a post-render downscale, or null = full resolution. 16-bit formats
     * (TIFF / PNG16) always export full-res — their save path writes the float buffer directly, so a
     * Bitmap resize doesn't apply; the sheet hides the Size dropdown for them and this short-circuits
     * to null (the stored size choice itself is left untouched). An empty/unset custom field
     * (customLongEdge <= 0) also means full resolution — never a MIN_CUSTOM_EDGE thumbnail.
     */
    fun targetLongEdge(): Int? = when {
        format.isHighBitDepth() -> null
        size == ExportSize.CUSTOM ->
            if (customLongEdge <= 0) null
            else customLongEdge.coerceIn(MIN_CUSTOM_EDGE, MAX_CUSTOM_EDGE)
        else -> size.longEdge
    }

    /**
     * Resolve and validate the complete output contract before decode/render starts. The selected
     * format owns sample depth; the six oracle-backed engine transforms own transfer/CCTF pairing.
     */
    fun outputDescriptor(
        colorSpace: ColorSpace,
        outputCctfEncoding: Boolean,
        apiLevel: Int,
    ): OutputDescriptor {
        val descriptor = if (format == ExportFormat.SCENE_LINEAR_TIFF) {
            OutputDescriptor.sceneLinearTiff(OutputDescriptor.fixedBitDepth(format))
        } else {
            OutputDescriptor.rendered(
                format = format,
                colorSpace = colorSpace,
                outputCctfEncoding = outputCctfEncoding,
                bitDepth = OutputDescriptor.fixedBitDepth(format),
            )
        }
        return descriptor.requireExportable(apiLevel)
    }

    companion object {
        const val MIN_CUSTOM_EDGE = 256
        const val MAX_CUSTOM_EDGE = 16384
    }
}

/**
 * Cold-start export defaults for a user who has never opened the export sheet.
 *
 * These are deliberately the everyday choice rather than the archival one: JPEG at quality 95,
 * sRGB, full source resolution, GPS stripped. A value the user explicitly saved in Settings or
 * in a previous sheet is read back through [AppSettings] and always wins; this object only
 * supplies the fallback and the sheet's reset target.
 */
object ExportDefaults {
    val FORMAT: ExportFormat = ExportFormat.JPEG
    const val JPEG_QUALITY: Int = 95
    val SIZE: ExportSize = ExportSize.FULL
    val COLOR_SPACE: ColorSpace = ColorSpace.SRGB
    const val KEEP_GPS: Boolean = false
    const val CUSTOM_LONG_EDGE: Int = 2048

    /** The complete initial sheet state, before any persisted user choice is applied. */
    fun options(): ExportOptions = ExportOptions(
        format = FORMAT,
        jpegQuality = JPEG_QUALITY,
        size = SIZE,
        customLongEdge = CUSTOM_LONG_EDGE,
        customName = "",
    )
}

/**
 * Which result actions a finished export can honestly offer.
 *
 * Both "查看" and "分享" need the real published MediaStore row: its content URI and the MIME
 * of the committed bytes. A restored SUCCESS from a previous process no longer has that URI,
 * so the actions are hidden rather than opening a guessed path or an empty chooser.
 */
data class ExportResultActions(val canView: Boolean, val canShare: Boolean)

fun exportResultActions(publishedUri: String?, publishedMimeType: String?): ExportResultActions {
    val hasPublishedRow = !publishedUri.isNullOrBlank() && !publishedMimeType.isNullOrBlank()
    return ExportResultActions(canView = hasPublishedRow, canShare = hasPublishedRow)
}

/**
 * Width/height after fitting [longEdge] to the longer side, preserving aspect ratio and never
 * enlarging (Lightroom's "Don't Enlarge"). Returns the input unchanged when it already fits.
 */
fun scaledDimensions(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
    val maxSide = maxOf(w, h)
    if (longEdge <= 0 || maxSide <= longEdge) return w to h
    val s = longEdge.toDouble() / maxSide
    return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
}

/**
 * The parsed state of the sheet's custom-long-edge field, kept separate from the numeric
 * [ExportOptions.customLongEdge] so the field can retain exactly what the user typed.
 *
 * [Original] is the empty field: full source resolution. [NonNumeric] and [OutOfRange] are real
 * input errors, not values to silently clamp — the sheet shows them inline and disables export.
 * [ExportOptions.targetLongEdge] still clamps as a last line of defence for restored sessions.
 */
sealed interface CustomEdgeValidation {
    data object Original : CustomEdgeValidation
    data class Valid(val value: Int) : CustomEdgeValidation
    data object NonNumeric : CustomEdgeValidation
    data object OutOfRange : CustomEdgeValidation
}

/** True when the field is empty (original size) or holds an in-range whole number. */
val CustomEdgeValidation.isValid: Boolean
    get() = this !is CustomEdgeValidation.NonNumeric && this !is CustomEdgeValidation.OutOfRange

/** The in-range value, or null for an empty field / an error. */
val CustomEdgeValidation.valueOrNull: Int?
    get() = (this as? CustomEdgeValidation.Valid)?.value

/**
 * Validate the raw custom-long-edge text. Deliberately does not sanitise or clamp: a typo must
 * surface as an error, and the user's exact characters stay in the field. Surrounding whitespace
 * is ignored; an empty/blank field means "use the original size". Only ASCII digits are accepted
 * so a pasted "12px" or "12.5" is an error rather than a surprise 12 or 125.
 */
fun validateCustomLongEdge(raw: String): CustomEdgeValidation {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return CustomEdgeValidation.Original
    if (trimmed.any { it !in '0'..'9' }) return CustomEdgeValidation.NonNumeric
    val value = trimmed.toIntOrNull() ?: return CustomEdgeValidation.OutOfRange
    if (value < ExportOptions.MIN_CUSTOM_EDGE || value > ExportOptions.MAX_CUSTOM_EDGE) {
        return CustomEdgeValidation.OutOfRange
    }
    return CustomEdgeValidation.Valid(value)
}

private val EXPORT_NAME_NOT_ALLOWED = Regex("[^\\p{L}\\p{N} _-]")
private val EXPORT_NAME_WHITESPACE = Regex("\\s+")

/**
 * The cleaned base filename (no extension), or null when nothing usable remains.
 *
 * Keeps Unicode letters and digits ([\\p{L}\\p{N}], so Chinese and precomposed accented Latin
 * names survive), spaces, underscores and hyphens. Path separators, punctuation and emoji are
 * dropped; whitespace collapses to underscores so the result stays portable. This is the single
 * sanitiser shared by [exportBaseName] and the sheet's live preview.
 */
fun sanitizeExportBaseName(custom: String): String? {
    val cleaned = custom.trim()
        .replace(EXPORT_NAME_NOT_ALLOWED, "")
        .replace(EXPORT_NAME_WHITESPACE, "_")
        .trim('_')
    return cleaned.ifEmpty { null }
}

/**
 * A safe export base filename (no extension): the sanitised [custom] name, or a timestamped default
 * when it is blank or sanitises to nothing. Chinese and accented names are preserved by
 * [sanitizeExportBaseName]; no caller ever receives an empty or path-bearing name.
 */
fun exportBaseName(custom: String, timestampMs: Long): String =
    sanitizeExportBaseName(custom) ?: "Spektrafilm_$timestampMs"
