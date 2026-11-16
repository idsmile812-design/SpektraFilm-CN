/*
 * Spektrafilm for Android — unit tests for the export-options model (export sheet §6a/§6b). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the resize maths (preserve aspect, never enlarge), the format-aware target long-edge (16-bit
 * always full-res, custom clamped), the custom-edge validation the sheet turns into inline errors,
 * and the Unicode-aware filename sanitiser + automatic-name fallback.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    private fun opts(
        format: ExportFormat = ExportFormat.JPEG,
        size: ExportSize = ExportSize.FULL,
        custom: Int = 2048,
    ) = ExportOptions(format, jpegQuality = 90, size = size, customLongEdge = custom, customName = "")

    @Test
    fun scaledDimensions_doesNotEnlarge() {
        assertEquals(1000 to 800, scaledDimensions(1000, 800, 2000))
        assertEquals(1000 to 800, scaledDimensions(1000, 800, 1000)) // exactly fits
    }

    @Test
    fun scaledDimensions_downscalesPreservingAspect() {
        assertEquals(2000 to 1500, scaledDimensions(4000, 3000, 2000))
        assertEquals(1500 to 2000, scaledDimensions(3000, 4000, 2000)) // portrait: long edge is height
    }

    @Test
    fun scaledDimensions_neverGoesBelowOne() {
        assertEquals(2 to 1, scaledDimensions(4000, 2, 2))
    }

    @Test
    fun targetLongEdge_bitmapFormats() {
        assertNull(opts(size = ExportSize.FULL).targetLongEdge())
        assertEquals(2048, opts(size = ExportSize.MEDIUM).targetLongEdge())
        assertEquals(3000, opts(size = ExportSize.CUSTOM, custom = 3000).targetLongEdge())
    }

    @Test
    fun targetLongEdge_customIsClamped() {
        assertEquals(ExportOptions.MIN_CUSTOM_EDGE, opts(size = ExportSize.CUSTOM, custom = 10).targetLongEdge())
        assertEquals(ExportOptions.MAX_CUSTOM_EDGE, opts(size = ExportSize.CUSTOM, custom = 999_999).targetLongEdge())
    }

    @Test
    fun targetLongEdge_emptyCustomFieldMeansFullRes() {
        // 0 = the sheet's custom-size field is empty/unset: full resolution, not a 256 px export.
        assertNull(opts(size = ExportSize.CUSTOM, custom = 0).targetLongEdge())
    }

    @Test
    fun targetLongEdge_16BitAlwaysFullRes() {
        assertNull(opts(format = ExportFormat.TIFF, size = ExportSize.MEDIUM).targetLongEdge())
        assertNull(opts(format = ExportFormat.PNG16, size = ExportSize.CUSTOM, custom = 1000).targetLongEdge())
    }

    @Test
    fun exportBaseName_defaultsWhenBlankOrFullyStripped() {
        assertEquals("Spektrafilm_123", exportBaseName("", 123))
        assertEquals("Spektrafilm_123", exportBaseName("   ", 123))
        assertEquals("Spektrafilm_123", exportBaseName("***", 123))
    }

    @Test
    fun exportBaseName_sanitisesToAPortableName() {
        assertEquals("My_Photo", exportBaseName("My Photo!", 1))
        assertEquals("Roll_12_frame", exportBaseName("Roll 12 — frame", 1))
        assertEquals("abc", exportBaseName("a/b\\c", 1))
        assertEquals("keep-this_1", exportBaseName("keep-this_1", 1))
    }

    // --- Custom long-edge validation (the sheet's inline error state) ---

    @Test
    fun validateCustomLongEdge_emptyOrBlankMeansOriginal() {
        assertEquals(CustomEdgeValidation.Original, validateCustomLongEdge(""))
        assertEquals(CustomEdgeValidation.Original, validateCustomLongEdge("   "))
        assertEquals(CustomEdgeValidation.Original, validateCustomLongEdge("\n\t "))
        assertTrue(validateCustomLongEdge("").isValid)
        assertNull(validateCustomLongEdge("").valueOrNull)
    }

    @Test
    fun validateCustomLongEdge_acceptsTheInclusiveBounds() {
        assertEquals(CustomEdgeValidation.Valid(256), validateCustomLongEdge("256"))
        assertEquals(CustomEdgeValidation.Valid(16384), validateCustomLongEdge("16384"))
        // Surrounding whitespace is ignored, not an error.
        assertEquals(CustomEdgeValidation.Valid(4096), validateCustomLongEdge(" 4096 "))
        assertEquals(4096, validateCustomLongEdge("4096").valueOrNull)
        assertTrue(validateCustomLongEdge("256").isValid)
        assertTrue(validateCustomLongEdge("16384").isValid)
    }

    @Test
    fun validateCustomLongEdge_rejectsOutOfRangeNumbers() {
        assertEquals(CustomEdgeValidation.OutOfRange, validateCustomLongEdge("255"))
        assertEquals(CustomEdgeValidation.OutOfRange, validateCustomLongEdge("16385"))
        assertEquals(CustomEdgeValidation.OutOfRange, validateCustomLongEdge("999999"))
        // Digits beyond Int.MAX_VALUE are still a numeric out-of-range value, not garbage.
        assertEquals(CustomEdgeValidation.OutOfRange, validateCustomLongEdge("99999999999999999999"))
        assertFalse(validateCustomLongEdge("255").isValid)
        assertFalse(validateCustomLongEdge("16385").isValid)
    }

    @Test
    fun validateCustomLongEdge_rejectsNonNumericText() {
        assertEquals(CustomEdgeValidation.NonNumeric, validateCustomLongEdge("abc"))
        assertEquals(CustomEdgeValidation.NonNumeric, validateCustomLongEdge("12px"))
        assertEquals(CustomEdgeValidation.NonNumeric, validateCustomLongEdge("12.5"))
        assertEquals(CustomEdgeValidation.NonNumeric, validateCustomLongEdge("-2048"))
        assertFalse(validateCustomLongEdge("abc").isValid)
        assertNull(validateCustomLongEdge("12px").valueOrNull)
    }

    // --- Unicode-aware export file names (sheet preview + export path) ---

    @Test
    fun sanitizeExportBaseName_keepsUnicodeLettersDigitsAndSeparators() {
        assertEquals("婚礼_2024", sanitizeExportBaseName("婚礼 2024"))
        assertEquals("Café", sanitizeExportBaseName("Café"))
        assertEquals("Renée_Noël", sanitizeExportBaseName("Renée Noël"))
        assertEquals("keep-this_1", sanitizeExportBaseName("keep-this_1"))
        // The sheet appends the real format suffix, so a typed dot is simply not allowed in the base.
        assertEquals("婚礼jpg", sanitizeExportBaseName("婚礼.jpg"))
    }

    @Test
    fun sanitizeExportBaseName_dropsPathSeparatorsAndEmoji() {
        assertEquals("abc", sanitizeExportBaseName("a/b\\c"))
        assertEquals("tmp_photo", sanitizeExportBaseName("tmp / photo"))
        assertEquals("Sunset", sanitizeExportBaseName("Sunset 🌅"))
        assertNull(sanitizeExportBaseName("🎞️🎞️"))
        assertNull(sanitizeExportBaseName("***"))
    }

    @Test
    fun exportBaseName_fallsBackToADatedAutomaticName() {
        // Nothing usable survives sanitising: the export still gets a real, non-empty name.
        assertEquals("Spektrafilm_42", exportBaseName("###", 42))
        assertEquals("Spektrafilm_42", exportBaseName("🙂", 42))
        assertEquals("Spektrafilm_42", exportBaseName("", 42))
        assertEquals("婚礼_2024", exportBaseName("婚礼 2024", 42))
    }

    @Test
    fun outputDescriptor_rejectsIllegalChoicesBeforeRender() {
        val jpeg = opts(format = ExportFormat.JPEG)
        assertEquals(
            ExistingExportClass.SDR_JPEG8,
            jpeg.outputDescriptor(ColorSpace.SRGB, outputCctfEncoding = true, apiLevel = 24)
                .existingExportClass,
        )
        assertTrue(runCatching {
            jpeg.outputDescriptor(ColorSpace.SRGB, outputCctfEncoding = false, apiLevel = 34)
        }.isFailure)
        assertTrue(runCatching {
            jpeg.outputDescriptor(ColorSpace.ACES2065_1, outputCctfEncoding = true, apiLevel = 34)
        }.isFailure)

        val adobeJpeg = jpeg.outputDescriptor(
            ColorSpace.ADOBE_RGB,
            outputCctfEncoding = true,
            apiLevel = 26,
        )
        assertEquals(26, adobeJpeg.minimumApi)
        assertTrue(runCatching { adobeJpeg.requirePlatformApi(25) }.isFailure)

        val scene = opts(format = ExportFormat.SCENE_LINEAR_TIFF).outputDescriptor(
            ColorSpace.ADOBE_RGB,
            outputCctfEncoding = true,
            apiLevel = 24,
        )
        assertEquals(OutputReference.SCENE_REFERRED, scene.reference)

        // Ultra HDR is exportable on API 34+ since #140 gave it a real, render-derived gain map.
        val ultraHdr = opts(format = ExportFormat.ULTRA_HDR).outputDescriptor(
            ColorSpace.SRGB,
            outputCctfEncoding = true,
            apiLevel = 34,
        )
        assertEquals(ExistingExportClass.ULTRA_HDR_SPATIAL_GAIN_MAP, ultraHdr.existingExportClass)
        assertTrue(runCatching {
            opts(format = ExportFormat.ULTRA_HDR).outputDescriptor(
                ColorSpace.SRGB,
                outputCctfEncoding = true,
                apiLevel = 33,
            )
        }.isFailure)
    }
}
