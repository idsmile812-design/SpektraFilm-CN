/*
 * Spektrafilm for Android — workbench selection v2 + real adjustment tests. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * These are JVM tests over the state mapping and the final SpektraParams, NOT evidence of device
 * rendering. They pin: v1 -> v2 migration, finishing round-trip, zero as a real OFF switch,
 * preset-relative (non-accumulating) offsets, and that the resulting engine parameters differ.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchSelectionTest {
    private fun preset(
        grainActive: Boolean = true,
        halationActive: Boolean = true,
    ) = BuiltInPreset(
        id = "test",
        name = "test",
        group = "test",
        description = "",
        params = JSONObject(
            """
            {
              "camera": {"exposureCompensationEv": 0.0, "autoExposure": true},
              "filmRender": {
                "grain": {"active": $grainActive, "agxParticleScale": [1.0, 1.0, 1.0]},
                "halation": {"active": $halationActive, "halationAmount": 1.0}
              }
            }
            """.trimIndent(),
        ),
    )

    private fun adjusted(selection: WorkbenchSelection): ParamsState {
        val state = ParamsState()
        BuiltInPresets.apply(preset(), state)
        selection.applyAdjustments(state)
        return state
    }

    @Test
    fun selectionRoundTripsWithoutChangingCaptureSetup() {
        val selection = WorkbenchSelection(
            presetId = "vision3_500t_2383_night",
            exposureEv = 1.5f,
            grain = 0.5f,
            halation = 1.5f,
            lens = WorkbenchLens.FRONT,
            flash = WorkbenchFlash.ON,
            border = WorkbenchBorder.WHITE,
            date = WorkbenchDate.DATE_TIME,
            captureTimestampMs = 1_800_000_000_000L,
        )

        assertEquals(selection, WorkbenchSelection.fromJson(selection.toJson().toString()))
        assertEquals(2, selection.toJson().optInt("version"))
    }

    @Test
    fun invalidDocumentFallsBackToKnownDefaults() {
        assertEquals(WorkbenchSelection(), WorkbenchSelection.fromJson("not-json"))
        assertEquals(WorkbenchSelection(), WorkbenchSelection.fromJson("{\"version\":3}"))
        assertEquals(WorkbenchSelection(), WorkbenchSelection.fromJson("{}"))
    }

    @Test
    fun versionOneDocumentMigratesToUndecoratedDefaults() {
        val migrated = WorkbenchSelection.fromJson(
            """{"version":1,"preset":"vision3_500t_2383_night","exposure":-0.5,"grain":2,"halation":0,"lens":"FRONT","flash":"OFF"}""",
        )

        assertEquals("vision3_500t_2383_night", migrated.presetId)
        assertEquals(-0.5f, migrated.exposureEv)
        assertEquals(WorkbenchBorder.NONE, migrated.border)
        assertEquals(WorkbenchDate.OFF, migrated.date)
        assertEquals(0L, migrated.captureTimestampMs)
        assertTrue(migrated.finishingConfig().isIdentity)
    }

    @Test
    fun versionTwoIllegalFinishingValuesAreReplacedOrClamped() {
        val selection = WorkbenchSelection.fromJson(
            """{"version":2,"preset":"x","border":"GLITTER","date":"TOMORROW","captureTimestampMs":-5}""",
        )

        assertEquals(WorkbenchBorder.NONE, selection.border)
        assertEquals(WorkbenchDate.OFF, selection.date)
        assertEquals(0L, selection.captureTimestampMs)

        val huge = WorkbenchSelection.fromJson(
            """{"version":2,"border":"BLACK","date":"DATE","captureTimestampMs":999999999999999999}""",
        )
        assertEquals(WorkbenchBorder.BLACK, huge.border)
        assertEquals(WorkbenchDate.DATE, huge.date)
        assertEquals(WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS, huge.captureTimestampMs)
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
        val state = adjusted(WorkbenchSelection(exposureEv = 0.5f, grain = 1.5f, halation = 0.5f))

        assertEquals(0.5f, state.exposureCompensationEv)
        assertEquals(0.5f, state.displayExposureEv)
        assertEquals(Triple(1.5f, 1.5f, 1.5f), state.grainParticleScale)
        assertEquals(0.5f, state.halHalationAmount)
    }

    @Test
    fun zeroGrainAndHalationTrulyDisableTheStages() {
        val off = adjusted(WorkbenchSelection(grain = 0f, halation = 0f))

        assertFalse(off.grainActive)
        assertFalse(off.halationActive)
        assertEquals(Triple(0.1f, 0.1f, 0.1f), off.grainParticleScale)
        assertEquals(0f, off.halHalationAmount)
        Presets.encode(off)
    }

    @Test
    fun positiveAmountsKeepThePresetActiveChoice() {
        val presetOn = adjusted(WorkbenchSelection(grain = 2f, halation = 2f))
        assertTrue(presetOn.grainActive)
        assertTrue(presetOn.halationActive)

        val state = ParamsState()
        BuiltInPresets.apply(preset(grainActive = false, halationActive = false), state)
        WorkbenchSelection(grain = 2f, halation = 2f).applyAdjustments(state)
        assertFalse("a positive amount must not force an inactive preset back on", state.grainActive)
        assertFalse("a positive amount must not force an inactive preset back on", state.halationActive)
    }

    @Test
    fun zeroCanBeRestoredByReapplyingThePresetBase() {
        val state = ParamsState()
        BuiltInPresets.apply(preset(), state)
        WorkbenchSelection(grain = 0f, halation = 0f).applyAdjustments(state)
        assertFalse(state.grainActive)
        assertFalse(state.halationActive)

        // A later edit always re-applies the preset first, exactly as MainActivity does.
        BuiltInPresets.apply(preset(), state)
        WorkbenchSelection(grain = 1f, halation = 1f).applyAdjustments(state)
        assertTrue(state.grainActive)
        assertTrue(state.halationActive)
    }

    @Test
    fun repeatedSliderUpdatesUseThePresetBaseInsteadOfAccumulating() {
        for (value in listOf(-2.0f, -0.5f, 0.5f, 2.0f)) {
            val state = adjusted(WorkbenchSelection(exposureEv = value))
            assertEquals("exposure $value accumulated", value, state.exposureCompensationEv)
        }
    }

    @Test
    fun repeatedFinishingChangesDoNotChangeEngineParameters() {
        val base = adjusted(WorkbenchSelection())
        val decorated = adjusted(
            WorkbenchSelection(
                border = WorkbenchBorder.WHITE,
                date = WorkbenchDate.DATE_TIME,
                captureTimestampMs = 1234L,
            ),
        )
        assertEquals(base.toParams(), decorated.toParams())
    }

    @Test
    fun theFinalSpektraParamsActuallyDifferFromTheUntouchedPreset() {
        val base = adjusted(WorkbenchSelection(grain = 1f, halation = 1f, exposureEv = 0f)).toParams()
        val plus = adjusted(WorkbenchSelection(grain = 2f, halation = 2f, exposureEv = 1f)).toParams()
        val minus = adjusted(WorkbenchSelection(grain = 0f, halation = 0f, exposureEv = -1f)).toParams()

        assertNotEquals(base, plus)
        assertNotEquals(base, minus)
        assertNotEquals(plus, minus)
        assertTrue(base.camera.autoExposure)
        assertFalse(plus.camera.autoExposure)
        assertFalse(minus.camera.autoExposure)
        assertEquals(1f, plus.camera.exposureCompensationEv)
        assertEquals(-1f, minus.camera.exposureCompensationEv)
        assertFalse(minus.filmRender.grain.active)
        assertFalse(minus.filmRender.halation.active)
    }
}
