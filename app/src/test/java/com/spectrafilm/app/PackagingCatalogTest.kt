/* Spektrafilm for Android — packaging asset provenance tests. GPLv3. */
package com.spectrafilm.app

import java.io.File
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagingCatalogTest {
    @Test
    fun bundledEntriesHaveRealStockIdsReadableIllustrationsAndCompleteRepositoryProvenance() {
        val entries = PackagingCatalog.parse(repoFile("app/src/main/assets/packaging/catalog.json").readText())
        assertEquals(3, entries.size)
        val stocks = JSONObject(
            repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText(),
        ).getJSONObject("stocks")
        val repo = "https://github.com/idsmile812-design/SpektraFilm-CN"
        assertEquals(
            setOf("kodak_portra_160", "kodak_portra_400", "kodak_portra_800"),
            entries.map { it.stockId }.toSet(),
        )

        for (entry in entries) {
            assertTrue(entry.stockId, stocks.has(entry.stockId))
            assertEquals("SpektraFilm CN contributors", entry.author)
            assertEquals(2026, entry.year)
            assertEquals("GPL-3.0-only", entry.license)
            assertEquals("$repo/blob/main/LICENSE", entry.licenseUrl)
            assertEquals(PackagingMediaType.BOX, entry.mediaType)
            assertTrue(entry.asset, entry.asset.endsWith("_illustration.jpg"))
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
            assertTrue(entry.modification.contains("not an exact reproduction"))

            val image = requireNotNull(
                ImageIO.read(repoFile("app/src/main/assets/${entry.asset}")),
            ) { "${entry.stockId} must be a readable JPEG" }
            assertEquals("${entry.stockId} width", 1200, image.width)
            assertEquals("${entry.stockId} height", 600, image.height)
        }
    }

    @Test
    fun missingOriginalPackagingHasNoFakeMapping() {
        val byStock = PackagingCatalog.parse(
            repoFile("app/src/main/assets/packaging/catalog.json").readText(),
        ).associateBy(PackagingEntry::stockId)
        assertNull(byStock["fujifilm_pro_400h"])
        assertNull(byStock["kodak_portra_endura"])
        assertNull(byStock["fujifilm_crystal_archive_typeii"])
        assertTrue(repoFile("app/src/main/assets/${PackagingCatalog.NOTICE_ASSET}").isFile)
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
