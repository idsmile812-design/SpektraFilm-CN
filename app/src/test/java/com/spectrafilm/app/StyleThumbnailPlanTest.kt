/*
 * Spektrafilm for Android — preset thumbnail planning tests. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleThumbnailPlanTest {
    @Test
    fun batchesPreserveFirstSeenFilmAndPresetOrder() {
        val batches = styleThumbnailBatches(
            listOf(
                preset("gold-a", "gold"),
                preset("gold-b", "gold"),
                preset("portra-a", "portra"),
                preset("gold-c", "gold"),
            ),
        )

        assertEquals(listOf("gold", "portra"), batches.map { it.filmProfile })
        assertEquals(listOf("gold-a", "gold-b", "gold-c"), batches[0].presets.map { it.id })
        assertEquals(listOf("portra-a"), batches[1].presets.map { it.id })
    }

    @Test
    fun emptyInputCreatesNoWork() {
        assertTrue(styleThumbnailBatches(emptyList()).isEmpty())
    }

    @Test
    fun aNewGenerationRejectsEveryOlderResult() {
        val generation = StyleThumbnailGeneration()
        val first = generation.next()
        val second = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun closingGalleryInvalidatesPublishedGeneration() {
        val generation = StyleThumbnailGeneration()
        val token = generation.next()

        generation.invalidate()

        assertFalse(generation.isCurrent(token))
    }

    private fun preset(id: String, film: String) = BuiltInPreset(
        id = id,
        name = id,
        group = "test",
        description = "",
        params = JSONObject(),
        filmProfile = film,
    )
}
