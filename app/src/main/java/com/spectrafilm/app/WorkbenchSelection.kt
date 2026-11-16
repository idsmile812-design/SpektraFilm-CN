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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("preset", presetId)
        .put("exposure", exposureEv.toDouble())
        .put("grain", grain.toDouble())
        .put("halation", halation.toDouble())
        .put("lens", lens.name)
        .put("flash", flash.name)

    fun applyAdjustments(state: ParamsState) {
        state.exposureCompensationEv = (state.exposureCompensationEv + exposureEv)
            .coerceIn(-4f, 4f)
        state.grainParticleScale = state.grainParticleScale.times(grain)
        state.halHalationAmount = (state.halHalationAmount * halation).coerceIn(0f, 4f)
    }

    companion object {
        const val DEFAULT_PRESET_ID = "portra400_endura_wedding"

        fun fromJson(raw: String): WorkbenchSelection {
            if (raw.isBlank()) return WorkbenchSelection()
            return runCatching {
                val json = JSONObject(raw)
                require(json.optInt("version") == 1)
                val default = WorkbenchSelection()
                WorkbenchSelection(
                    presetId = json.optString("preset", default.presetId)
                        .takeIf { it.isNotBlank() && it.length <= 128 } ?: default.presetId,
                    exposureEv = json.finiteFloat("exposure", default.exposureEv, -2f, 2f),
                    grain = json.finiteFloat("grain", default.grain, 0f, 2f),
                    halation = json.finiteFloat("halation", default.halation, 0f, 2f),
                    lens = enumValueOrDefault(json.optString("lens"), default.lens),
                    flash = enumValueOrDefault(json.optString("flash"), default.flash),
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

        private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
            runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}

private fun Triple<Float, Float, Float>.times(multiplier: Float): Triple<Float, Float, Float> =
    Triple(first * multiplier, second * multiplier, third * multiplier)
