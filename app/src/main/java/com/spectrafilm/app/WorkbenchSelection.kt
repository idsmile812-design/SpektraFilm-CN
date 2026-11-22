/*
 * Spektrafilm for Android — single-page capture workbench state. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.json.JSONObject

internal enum class WorkbenchLens { BACK, FRONT }
internal enum class WorkbenchFlash { AUTO, OFF, ON }

internal data class WorkbenchSelection(
    val presetId: String = DEFAULT_PRESET_ID,
    val exposureEv: Float = 0f,
    val grain: Float = 1f,
    val halation: Float = 1f,
    val lens: WorkbenchLens = WorkbenchLens.BACK,
    val flash: WorkbenchFlash = WorkbenchFlash.AUTO,
    val border: WorkbenchBorder = WorkbenchBorder.NONE,
    val date: WorkbenchDate = WorkbenchDate.OFF,
    val captureTimestampMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("preset", presetId)
        .put("exposure", finiteOr(exposureEv, 0f).toDouble())
        .put("grain", finiteOr(grain, 1f).toDouble())
        .put("halation", finiteOr(halation, 1f).toDouble())
        .put("lens", lens.name)
        .put("flash", flash.name)
        .put("border", border.name)
        .put("date", date.name)
        .put("captureTimestampMs", captureTimestampMs.coerceIn(0L, WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS))

    fun finishingConfig(): WorkbenchFinishingConfig =
        WorkbenchFinishingConfig(
            border = border,
            date = date,
            captureTimestampMs = captureTimestampMs,
        )

    /**
     * Apply the workbench offsets ON TOP OF the currently selected preset. The caller must have
     * applied the preset first (MainActivity re-applies it for every update), which is what keeps
     * repeated slider updates from accumulating.
     *
     * A zero grain/halation amount is a real OFF switch: it disables the stage outright rather
     * than multiplying its strength by zero. A positive amount leaves the preset's own
     * active/inactive choice alone.
     */
    fun applyAdjustments(state: ParamsState) {
        val exposure = exposureEv.takeIf { it.isFinite() }?.coerceIn(-2f, 2f) ?: 0f
        val grainScale = grain.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f
        val halationScale = halation.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f

        state.exposureCompensationEv = (state.exposureCompensationEv + exposure)
            .coerceIn(-4f, 4f)
        if (exposure != 0f) state.autoExposure = false
        state.displayExposureEv = exposure
        state.grainParticleScale = state.grainParticleScale.times(grainScale)
        if (grainScale == 0f) state.grainActive = false
        state.halHalationAmount = (state.halHalationAmount * halationScale).coerceIn(0f, 4f)
        if (halationScale == 0f) state.halationActive = false
    }

    companion object {
        const val DEFAULT_PRESET_ID = "portra400_endura_wedding"
        const val JSON_VERSION = 2

        fun fromJson(raw: String): WorkbenchSelection {
            if (raw.isBlank()) return WorkbenchSelection()
            return runCatching {
                val json = JSONObject(raw)
                val version = json.optInt("version", 0)
                // v1 documents simply omit the finishing keys; every default below is the v1
                // behaviour, so migration is loss-less.
                require(version == 1 || version == JSON_VERSION)
                val default = WorkbenchSelection()
                WorkbenchSelection(
                    presetId = json.optString("preset", default.presetId)
                        .takeIf { it.isNotBlank() && it.length <= 128 } ?: default.presetId,
                    exposureEv = json.finiteFloat("exposure", default.exposureEv, -2f, 2f),
                    grain = json.finiteFloat("grain", default.grain, 0f, 2f),
                    halation = json.finiteFloat("halation", default.halation, 0f, 2f),
                    lens = enumValueOrDefault(json.optString("lens"), default.lens),
                    flash = enumValueOrDefault(json.optString("flash"), default.flash),
                    border = enumValueOrDefault(json.optString("border"), default.border),
                    date = enumValueOrDefault(json.optString("date"), default.date),
                    captureTimestampMs = json.finiteTimestamp(
                        "captureTimestampMs",
                        default.captureTimestampMs,
                    ),
                )
            }.getOrElse { WorkbenchSelection() }
        }

        private fun JSONObject.finiteFloat(
            key: String,
            default: Float,
            minimum: Float,
            maximum: Float,
        ): Float {
            val value = optDouble(key, default.toDouble()).toFloat()
            return if (value.isFinite()) value.coerceIn(minimum, maximum) else default
        }

        private fun JSONObject.finiteTimestamp(key: String, default: Long): Long {
            val raw = opt(key) ?: return default
            val value = (raw as? Number)?.toLong()
                ?: (raw as? String)?.toLongOrNull()
                ?: return default
            return value.coerceIn(0L, WORKBENCH_MAX_CAPTURE_TIMESTAMP_MS)
        }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
            runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}

private fun finiteOr(value: Float, fallback: Float): Float =
    if (value.isFinite()) value else fallback

private fun Triple<Float, Float, Float>.times(multiplier: Float): Triple<Float, Float, Float> =
    Triple(
        (first * multiplier).coerceIn(0.1f, 5f),
        (second * multiplier).coerceIn(0.1f, 5f),
        (third * multiplier).coerceIn(0.1f, 5f),
    )
