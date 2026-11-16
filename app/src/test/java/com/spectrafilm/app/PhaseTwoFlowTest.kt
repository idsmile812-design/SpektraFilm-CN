/*
 * Spektrafilm for Android — phase-two minimal-flow defaults. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pins the pure decisions behind the first-run flow: the cold-start export contract, which
 * panel a finished import opens, and which result actions a published row can honestly offer.
 */
package com.spectrafilm.app

import com.spectrafilm.engine.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTwoFlowTest {

    @Test
    fun newInstallExportDefaultsAreJpeg95SrgbFullResolutionWithoutGps() {
        val options = ExportDefaults.options()
        assertEquals(ExportFormat.JPEG, options.format)
        assertEquals(95, options.jpegQuality)
        assertEquals(ExportSize.FULL, options.size)
        assertNull(options.targetLongEdge())
        assertEquals(ColorSpace.SRGB, ExportDefaults.COLOR_SPACE)
        assertFalse(ExportDefaults.KEEP_GPS)
    }

    @Test
    fun defaultOptionsResolveToTheSupportedSdrJpegContract() {
        val descriptor = ExportDefaults.options().outputDescriptor(
            colorSpace = ExportDefaults.COLOR_SPACE,
            outputCctfEncoding = true,
            apiLevel = 34,
        )
        assertEquals(ExistingExportClass.SDR_JPEG8, descriptor.existingExportClass)
        assertEquals(OutputBitDepth.UINT8, descriptor.bitDepth)
    }

    @Test
    fun importingANewPhotographOpensThePresetsPanel() {
        assertEquals(
            Category.PRESETS,
            categoryAfterSourceImport(switched = true, kind = SourceKind.PHOTO),
        )
        assertEquals(
            Category.PRESETS,
            categoryAfterSourceImport(switched = true, kind = SourceKind.RAW),
        )
    }

    @Test
    fun unchangedOrSyntheticSourcesDoNotStealTheOpenPanel() {
        assertNull(categoryAfterSourceImport(switched = false, kind = SourceKind.PHOTO))
        assertNull(categoryAfterSourceImport(switched = false, kind = SourceKind.RAW))
        assertNull(categoryAfterSourceImport(switched = true, kind = SourceKind.DEMO))
    }

    @Test
    fun successWithoutAPublishedRowHidesViewAndShare() {
        val none = exportResultActions(publishedUri = null, publishedMimeType = null)
        assertFalse(none.canView)
        assertFalse(none.canShare)

        val blank = exportResultActions(publishedUri = " ", publishedMimeType = "image/jpeg")
        assertFalse(blank.canView)
        assertFalse(blank.canShare)
    }

    @Test
    fun committedContentUriAndMimeEnableViewAndShare() {
        val actions = exportResultActions(
            publishedUri = "content://media/external/images/media/42",
            publishedMimeType = "image/jpeg",
        )
        assertTrue(actions.canView)
        assertTrue(actions.canShare)
    }

    @Test
    fun systemBackReturnsToTheScreenThatOpenedEachNestedTool() {
        assertEquals(Screen.SETTINGS, parentScreenForBack(Screen.DIAGNOSTICS))
        assertEquals(Screen.ABOUT, parentScreenForBack(Screen.HOWTO))
        assertEquals(Screen.EDITOR, parentScreenForBack(Screen.SETTINGS))
        assertEquals(Screen.EDITOR, parentScreenForBack(Screen.ABOUT))
        assertEquals(Screen.EDITOR, parentScreenForBack(Screen.CURVES_FILM))
        assertEquals(Screen.EDITOR, parentScreenForBack(Screen.CURVES_PRINT))
    }

    @Test
    fun magnifierClosesBeforeCropMaskSamplePanelAndExit() {
        fun action(
            magnifier: Boolean = false,
            chromeHidden: Boolean = false,
            crop: Boolean = false,
            mask: Boolean = false,
            sample: Boolean = false,
            exportMask: Boolean = false,
            panel: Boolean = true,
        ) = editorBackAction(
            magnifierOpen = magnifier,
            chromeHidden = chromeHidden,
            cropOverlayOpen = crop,
            maskOverlayOpen = mask,
            sampleOverlayOpen = sample,
            exportMaskVisible = exportMask,
            panelOpen = panel,
        )

        // Even with every other overlay nominally set, the magnifier is the top surface.
        assertEquals(
            EditorBackAction.CLOSE_MAGNIFIER,
            action(magnifier = true, chromeHidden = true, crop = true, mask = true, sample = true),
        )
        assertEquals(EditorBackAction.SHOW_CHROME, action(chromeHidden = true, crop = true))
        assertEquals(EditorBackAction.CLOSE_CROP, action(crop = true, mask = true))
        assertEquals(EditorBackAction.CLOSE_MASK, action(mask = true, sample = true))
        assertEquals(EditorBackAction.CLOSE_SAMPLE, action(sample = true))
        assertEquals(EditorBackAction.CLOSE_PANEL, action())
        assertEquals(EditorBackAction.EXIT, action(panel = false))
    }

    @Test
    fun theExportMaskOwnsBackWhileItIsVisible() {
        assertEquals(
            EditorBackAction.NONE,
            editorBackAction(
                magnifierOpen = true,
                chromeHidden = false,
                cropOverlayOpen = false,
                maskOverlayOpen = false,
                sampleOverlayOpen = false,
                exportMaskVisible = true,
                panelOpen = true,
            ),
        )
    }
}
