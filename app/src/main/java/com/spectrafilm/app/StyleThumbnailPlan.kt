/*
 * Spektrafilm for Android — bounded preset thumbnail planning. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import java.util.concurrent.atomic.AtomicLong

internal data class StyleThumbnailBatch(
    val filmProfile: String,
    val presets: List<BuiltInPreset>,
)

internal fun styleThumbnailBatches(presets: List<BuiltInPreset>): List<StyleThumbnailBatch> {
    val batches = LinkedHashMap<String, MutableList<BuiltInPreset>>()
    for (preset in presets) batches.getOrPut(preset.filmProfile) { mutableListOf() }.add(preset)
    return batches.map { (film, batch) -> StyleThumbnailBatch(film, batch) }
}

internal class StyleThumbnailGeneration {
    private val current = AtomicLong()

    fun next(): Long = current.incrementAndGet()

    fun invalidate() {
        current.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = current.get() == token
}
