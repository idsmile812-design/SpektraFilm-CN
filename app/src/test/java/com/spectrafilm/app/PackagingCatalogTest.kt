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
    fun bundledEntriesHaveRealStockIdsReadableImagesAndCompleteProvenance() {
        val entries = PackagingCatalog.parse(repoFile("app/src/main/assets/packaging/catalog.json").readText())
        assertEquals(3, entries.size)
        val stocks = JSONObject(
            repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText(),
        ).getJSONObject("stocks")

        for (entry in entries) {
            assertTrue(entry.stockId, stocks.has(entry.stockId))
            assertTrue(entry.sourcePage.startsWith("https://commons.wikimedia.org/wiki/File:"))
            assertTrue(entry.sourceFile.startsWith("https://upload.wikimedia.org/"))
            assertTrue(entry.author.isNotBlank())
            assertTrue(entry.license.isNotBlank())
            assertTrue(entry.licenseUrl.startsWith("https://"))
            val image = ImageIO.read(repoFile("app/src/main/assets/${entry.asset}"))
            assertTrue("${entry.stockId} must be a readable JPEG", image != null && image.width > 0)
        }
        assertEquals(PackagingMediaType.BOX, entries.first { it.stockId == "kodak_portra_160" }.mediaType)
        assertEquals(PackagingMediaType.BOX, entries.first { it.stockId == "kodak_portra_800" }.mediaType)
        assertEquals(PackagingMediaType.CARTRIDGE, entries.first { it.stockId == "kodak_portra_400" }.mediaType)
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
