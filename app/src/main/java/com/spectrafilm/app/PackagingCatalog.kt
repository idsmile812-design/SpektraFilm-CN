/*
 * Spektrafilm for Android — verified packaging-media index. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.content.Context
import org.json.JSONObject

internal enum class PackagingMediaType(val wireValue: String) {
    BOX("box"),
    CARTRIDGE("cartridge"),
}

internal data class PackagingEntry(
    val stockId: String,
    val asset: String,
    val mediaType: PackagingMediaType,
    val year: Int,
    val author: String,
    val sourcePage: String,
    val sourceFile: String,
    val license: String,
    val licenseUrl: String,
    val modification: String,
)

internal object PackagingCatalog {
    const val ASSET_PATH = "packaging/catalog.json"
    const val NOTICE_ASSET = "packaging/NOTICE.md"

    fun load(context: Context): List<PackagingEntry> {
        val json = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(json)
    }

    internal fun parse(json: String): List<PackagingEntry> {
        val root = JSONObject(json)
        require(root.getInt("schema") == 1) { "unsupported packaging schema" }
        val array = root.getJSONArray("items")
        val ids = mutableSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val entry = PackagingEntry(
                    stockId = requiredText(item, "stockId"),
                    asset = requiredText(item, "asset"),
                    mediaType = PackagingMediaType.entries.firstOrNull {
                        it.wireValue == item.getString("mediaType")
                    } ?: throw IllegalArgumentException("unknown packaging mediaType"),
                    year = item.getInt("year"),
                    author = requiredText(item, "author"),
                    sourcePage = requiredHttps(item, "sourcePage"),
                    sourceFile = requiredHttps(item, "sourceFile"),
                    license = requiredText(item, "license"),
                    licenseUrl = requiredHttps(item, "licenseUrl"),
                    modification = requiredText(item, "modification"),
                )
                require(entry.year in 1900..2100) { "invalid packaging year" }
                require(
                    entry.asset.startsWith("packaging/") &&
                        !entry.asset.contains("..") &&
                        !entry.asset.contains("//") &&
                        entry.asset.endsWith(".jpg", ignoreCase = true)
                ) { "unsafe packaging asset path" }
                require(ids.add(entry.stockId)) { "duplicate packaging stockId" }
                add(entry)
            }
        }
    }

    private fun requiredText(item: JSONObject, key: String): String =
        item.getString(key).trim().also { require(it.isNotEmpty()) { "blank packaging $key" } }

    private fun requiredHttps(item: JSONObject, key: String): String =
        requiredText(item, key).also { require(it.startsWith("https://")) { "packaging $key must be HTTPS" } }
}
