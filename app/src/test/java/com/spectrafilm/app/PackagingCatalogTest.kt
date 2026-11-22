/* Spektrafilm for Android — packaging asset provenance tests. GPLv3. */
package com.spectrafilm.app

import java.io.File
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagingCatalogTest {
    @Test
    fun bundledEntriesCoverEveryPresetStockWithReadableIllustrationsAndCompleteProvenance() {
        val entries = PackagingCatalog.parse(
            repoFile("app/src/main/assets/packaging/catalog.json").readText(),
        )
        val stocks = JSONObject(
            repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText(),
        ).getJSONObject("stocks")
        val expected = presetFilmProfiles()

        // The workbench gets its twenty stocks from params.filmProfile in the
        // shipped presets; the artwork set must be derived from that same source
        // and must cover it exactly, with no extra and no missing entry.
        assertEquals("preset-derived stocks", 20, expected.size)
        assertEquals(
            setOf(
                "kodak_portra_160",
                "kodak_portra_400",
                "kodak_portra_800",
                "kodak_portra_800_push1",
                "kodak_portra_800_push2",
                "kodak_ektar_100",
                "kodak_gold_200",
                "kodak_ultramax_400",
                "fujifilm_pro_400h",
                "fujifilm_c200",
                "fujifilm_xtra_400",
                "kodak_ektachrome_100",
                "fujifilm_provia_100f",
                "fujifilm_velvia_100",
                "kodak_kodachrome_64",
                "kodak_vision3_50d",
                "kodak_vision3_250d",
                "kodak_vision3_200t",
                "kodak_vision3_500t",
                "kodak_verita_200d",
            ),
            expected,
        )
        assertEquals(20, entries.size)
        assertEquals(expected, entries.map(PackagingEntry::stockId).toSet())

        val repo = "https://github.com/idsmile812-design/SpektraFilm-CN"
        val forbiddenGroups = setOf("print_film", "print_paper")

        for (entry in entries) {
            assertTrue("${entry.stockId} must be a real catalogue stock", stocks.has(entry.stockId))
            val stock = stocks.getJSONObject(entry.stockId)
            assertFalse(
                "${entry.stockId} must be a camera film, not print film/paper",
                stock.getString("group") in forbiddenGroups,
            )
            assertEquals("${entry.stockId} kind", "film", stock.getString("kind"))

            assertEquals("SpektraFilm CN contributors", entry.author)
            assertEquals(2026, entry.year)
            assertEquals("GPL-3.0-only", entry.license)
            assertEquals("$repo/blob/main/LICENSE", entry.licenseUrl)
            assertEquals(PackagingMediaType.BOX, entry.mediaType)
            assertEquals("packaging/${entry.stockId}_illustration.jpg", entry.asset)
            assertTrue(entry.asset.endsWith("_illustration.jpg"))
            assertEquals(
                "$repo/blob/main/app/src/main/assets/${entry.asset}",
                entry.sourcePage,
            )
            assertEquals(
                "https://raw.githubusercontent.com/idsmile812-design/SpektraFilm-CN/main/" +
                    "app/src/main/assets/${entry.asset}",
                entry.sourceFile,
            )
            assertTrue(entry.modification.contains("Original flat illustration"))
            assertTrue(entry.modification.contains("not a photograph"))
            assertTrue(entry.modification.contains("not a scan"))
            assertTrue(entry.modification.contains("not an exact reproduction"))

            val image = requireNotNull(
                ImageIO.read(repoFile("app/src/main/assets/${entry.asset}")),
            ) { "${entry.stockId} must be a readable JPEG" }
            assertEquals("${entry.stockId} width", 1200, image.width)
            assertEquals("${entry.stockId} height", 600, image.height)
        }
    }

    @Test
    fun printFilmAndPaperHaveNoFakePackaging() {
        val byStock = PackagingCatalog.parse(
            repoFile("app/src/main/assets/packaging/catalog.json").readText(),
        ).associateBy(PackagingEntry::stockId)
        val stocks = JSONObject(
            repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText(),
        ).getJSONObject("stocks")

        // A print film or a print paper is not a camera film. Every stock in
        // those groups must stay on an information card, never on a box.
        val keys = stocks.keys()
        while (keys.hasNext()) {
            val stockId = keys.next()
            if (stocks.getJSONObject(stockId).getString("group") in
                setOf("print_film", "print_paper")
            ) {
                assertNull("$stockId must not have an illustration", byStock[stockId])
            }
        }

        assertNull(byStock["kodak_portra_endura"])
        assertNull(byStock["fujifilm_crystal_archive_typeii"])
        assertNull(byStock["kodak_2383"])
        assertNull(byStock["kodak_2393"])

        // Pro 400H used to fall back to a card; it now has its own artwork.
        assertTrue(byStock.containsKey("fujifilm_pro_400h"))
        assertTrue(repoFile("app/src/main/assets/${PackagingCatalog.NOTICE_ASSET}").isFile)
    }

    private fun presetFilmProfiles(): Set<String> {
        val presets = JSONObject(
            repoFile("engine/spektra-core/src/main/assets/spektra/presets.json").readText(),
        ).getJSONArray("presets")
        val profiles = linkedSetOf<String>()
        for (index in 0 until presets.length()) {
            val params = presets.getJSONObject(index).optJSONObject("params") ?: continue
            val profile = params.optString("filmProfile").trim()
            if (profile.isNotEmpty()) profiles += profile
        }
        return profiles
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative")
    }
}
