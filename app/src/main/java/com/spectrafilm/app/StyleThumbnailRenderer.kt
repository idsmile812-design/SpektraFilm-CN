/*
 * Spektrafilm for Android — real current-photo preset thumbnails. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import com.spectrafilm.engine.AppRenderOutcome
import com.spectrafilm.engine.SimResult
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class StyleThumbnailRenderer(
    private val engine: SpektraEngine,
    private val source: SourceDecodeRequest,
    private val baseParamsJson: String,
    private val maxEdge: Int = 256,
) {
    suspend fun render(
        presets: List<BuiltInPreset>,
        publish: suspend (String, Bitmap) -> Boolean,
    ) = withContext(Dispatchers.Default) {
        for (batch in styleThumbnailBatches(presets)) {
            ensureActive()
            val decoded = try {
                decodeSourceRequest(source.copy(filmProfile = batch.filmProfile), maxEdge).image
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Diag.w("thumbnail source decode failed: ${failure.message}")
                continue
            }
            decoded.use { image ->
                for (preset in batch.presets) {
                    ensureActive()
                    var renderId = 0L
                    var bitmap: Bitmap? = null
                    try {
                        val state = ParamsState()
                        Presets.decode(JSONObject(baseParamsJson), state)
                        BuiltInPresets.apply(preset, state)
                        val params = state.toParams(
                            previewMaxSizeOverride = maxEdge,
                            skipGrainHalation = true,
                        )
                        bitmap = runCancellableNative(
                            onLateResult = { late ->
                                late.reportOutcome(AppRenderOutcome.SUPERSEDED)
                                late.close()
                            },
                        ) { cancellation ->
                            engine.simulatePreview(image, params, cancellation = cancellation)
                        }.use { result ->
                            renderId = result.renderId
                            simResultToBitmapGraded(
                                result,
                                state.savingCctfEncoding,
                                state.saturation,
                                state.vibrance,
                                state.gamutCompress,
                                state.localAdjustments,
                            )
                        }
                        ensureActive()
                        val rendered = bitmap
                        if (rendered != null && publish(preset.id, rendered)) {
                            SimResult.reportOutcome(renderId, AppRenderOutcome.CONSUMED)
                            bitmap = null
                        } else {
                            SimResult.reportOutcome(renderId, AppRenderOutcome.SUPERSEDED)
                            return@withContext
                        }
                    } catch (cancelled: CancellationException) {
                        if (renderId != 0L) {
                            SimResult.reportOutcome(renderId, AppRenderOutcome.CANCELLED)
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (renderId != 0L) {
                            SimResult.reportOutcome(renderId, AppRenderOutcome.FAILED)
                        }
                        Diag.w("thumbnail render failed: ${failure.message}")
                    } finally {
                        bitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
            }
        }
    }
}
