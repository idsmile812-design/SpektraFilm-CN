/*
 * Spektrafilm for Android — workbench selection tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkbenchSelectionTest {
    @Test
    fun selectionRoundTripsWithoutChangingCaptureSetup() {
        val selection = WorkbenchSelection(
            presetId = "vision3_500t_2383_night",
            exposureEv = 1.5f,
            grain = 0.5f,
            halation = 1.5f,
            lens = WorkbenchLens.FRONT,
            flash = WorkbenchFlash.ON,
        )

        assertEquals(selection, WorkbenchSelection.fromJson(selection.toJson().toString()))
    }

    @Test
    fun invalidDocumentFallsBackToKnownDefaults() {
        assertEquals(WorkbenchSelection(), WorkbenchSelection.fromJson("not-json"))
        assertEquals(WorkbenchSelection(), WorkbenchSelection.fromJson("{\"version\":2}"))
    }

    @Test
    fun outOfRangeAndUnknownValuesAreBoundedOrReplaced() {
        val selection = WorkbenchSelection.fromJson(
            """{"version":1,"preset":"","exposure":99,"grain":-2,"halation":99,"lens":"unknown","flash":"unknown"}""",
        )

        assertEquals(WorkbenchSelection.DEFAULT_PRESET_ID, selection.presetId)
        assertEquals(2f, selection.exposureEv)
        assertEquals(0f, selection.grain)
        assertEquals(2f, selection.halation)
        assertEquals(WorkbenchLens.BACK, selection.lens)
        assertEquals(WorkbenchFlash.AUTO, selection.flash)
    }

    @Test
    fun adjustmentsApplyOnceOnTopOfTheSelectedPreset() {
        val state = ParamsState()
        val preset = BuiltInPreset(
            id = "test",
            name = "test",
            group = "test",
            description = "",
            params = org.json.JSONObject(),
        )
        BuiltInPresets.apply(preset, state)
        val selection = WorkbenchSelection(exposureEv = 0.5f, grain = 1.5f, halation = 0.5f)

        selection.applyAdjustments(state)

        assertEquals(0.5f, state.exposureCompensationEv)
        assertEquals(Triple(1.2f, 1.5f, 3f), state.grainParticleScale)
        assertEquals(0.5f, state.halHalationAmount)
    }

    @Test
    fun repeatedSliderUpdatesUseThePresetBaseInsteadOfAccumulating() {
        val state = ParamsState()
        val base = ParamsState()

        for (value in listOf(0.5f, 1.0f, 1.5f)) {
            state.exposureCompensationEv = base.exposureCompensationEv
            WorkbenchSelection(exposureEv = value).applyAdjustments(state)
            assertEquals(value, state.exposureCompensationEv)
        }
    }
}
