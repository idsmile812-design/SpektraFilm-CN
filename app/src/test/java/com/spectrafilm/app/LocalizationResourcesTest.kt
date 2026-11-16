/*
 * Spektrafilm for Android — Simplified Chinese resource consistency. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The app ships five English string files and their values-zh-rCN counterparts. These tests
 * parse both sets as XML and pin the localisation contract: identical keys in identical order,
 * no duplicate keys, matching translatable/formatted attributes, English text for
 * translatable="false", identical format placeholders, non-empty translations, and coverage of
 * the stock-catalog / built-in-preset ids that live in the engine's JSON assets.
 */
package com.spectrafilm.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {

    private val files = listOf(
        "strings.xml",
        "strings_screens.xml",
        "strings_editor.xml",
        "strings_tools.xml",
        "strings_widgets.xml",
    )

    private data class StringEntry(
        val name: String,
        val translatable: Boolean,
        val formatted: Boolean,
        val text: String,
    )

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun parse(file: File): List<StringEntry> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildList {
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                add(
                    StringEntry(
                        name = e.getAttribute("name"),
                        translatable = e.getAttribute("translatable") != "false",
                        formatted = e.getAttribute("formatted") != "false",
                        text = e.textContent,
                    ),
                )
            }
        }
    }

    /**
     * `%1$d`, `%2$s`, `%d` … preserved verbatim in both files. A literal percent in
     * "100% crop" is deliberately not a format spec: it is preceded by a digit and its
     * conversion letter is not one Android recognises, so the match requires a real
     * conversion character and a non-digit prefix.
     */
    private val placeholder = Regex(
        """(?<![\d%])%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaA]""",
    )

    private fun placeholders(text: String): List<String> =
        placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun default(name: String): List<StringEntry> =
        parse(repoFile("app/src/main/res/values/$name"))

    private fun chinese(name: String): List<StringEntry> =
        parse(repoFile("app/src/main/res/values-zh-rCN/$name"))

    @Test
    fun everyTranslatedFileParsesAndMatchesTheDefaultKeysInOrder() {
        for (file in files) {
            val english = default(file)
            val chinese = chinese(file)
            assertTrue("$file has no strings", english.isNotEmpty())
            assertEquals("$file: key list differs", english.map { it.name }, chinese.map { it.name })
            assertEquals("$file: duplicate default keys", english.size, english.map { it.name }.toSet().size)
            assertEquals("$file: duplicate Chinese keys", chinese.size, chinese.map { it.name }.toSet().size)
        }
    }

    @Test
    fun attributesAndUntranslatableTextMatch() {
        for (file in files) {
            val english = default(file).associateBy { it.name }
            val chinese = chinese(file).associateBy { it.name }
            for ((name, e) in english) {
                val z = chinese.getValue(name)
                assertEquals("$file/$name: translatable attribute", e.translatable, z.translatable)
                assertEquals("$file/$name: formatted attribute", e.formatted, z.formatted)
                if (!e.translatable) {
                    assertEquals("$file/$name: translatable=false must stay verbatim", e.text, z.text)
                }
            }
        }
    }

    @Test
    fun placeholdersAreIdentical() {
        for (file in files) {
            val english = default(file).associateBy { it.name }
            for (z in chinese(file)) {
                val e = english.getValue(z.name)
                assertEquals(
                    "$file/${z.name}: format placeholders changed",
                    placeholders(e.text),
                    placeholders(z.text),
                )
            }
        }
    }

    @Test
    fun everyTranslatableStringHasANonBlankChineseValue() {
        for (file in files) {
            for (z in chinese(file).filter { it.translatable }) {
                assertTrue("$file/${z.name}: blank translation", z.text.isNotBlank())
            }
        }
    }

    @Test
    fun appNameIsChineseWhileAboutKeepsTheProductNames() {
        val chinese = chinese("strings.xml").associateBy { it.name }
        assertEquals("光谱胶片", chinese.getValue("app_name").text)
        assertEquals("Spektrafilm", chinese.getValue("screen_about_product_name").text)
        assertEquals("Spektrafilm for Android", chinese.getValue("screen_about_android_name").text)
        assertFalse(chinese.getValue("screen_about_product_name").translatable)
        assertFalse(chinese.getValue("screen_about_android_name").translatable)
    }

    @Test
    fun aboutLegalTextMatchesTheCanonicalProvenanceConstants() {
        val english = default("strings_screens.xml").associateBy { it.name }
        assertEquals(
            LIBRAW_LICENSE_STATUS,
            english.getValue("screen_about_libraw_license_status").text,
        )
        assertEquals(
            LIBRAW_ROUTE_STATUS,
            english.getValue("screen_about_libraw_route_status").text,
        )
    }

    @Test
    fun stockCatalogMetadataCoversEveryAssetEntry() {
        val catalog = JSONObject(repoFile("engine/spektra-core/src/main/assets/spektra/catalog.json").readText())
        val english = default("strings_editor.xml").associateBy { it.name }
        val chinese = chinese("strings_editor.xml").associateBy { it.name }
        fun bothExist(key: String) {
            assertNotNull("missing default resource $key", english[key])
            assertNotNull("missing Chinese resource $key", chinese[key])
            assertTrue("blank Chinese resource $key", chinese.getValue(key).text.isNotBlank())
        }
        val groups = catalog.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            bothExist("stock_group_${groups.getJSONObject(i).getString("id")}")
        }
        bothExist("stock_group_other")
        bothExist("stock_balance_daylight")
        bothExist("stock_balance_tungsten")
        val stocks = catalog.getJSONObject("stocks")
        for (id in stocks.keys()) bothExist("stock_summary_$id")
    }

    @Test
    fun builtInPresetMetadataCoversEveryAssetEntry() {
        val presets = JSONObject(repoFile("engine/spektra-core/src/main/assets/spektra/presets.json").readText())
            .getJSONArray("presets")
        val english = default("strings_editor.xml").associateBy { it.name }
        val chinese = chinese("strings_editor.xml").associateBy { it.name }
        fun bothExist(key: String) {
            assertNotNull("missing default resource $key", english[key])
            assertNotNull("missing Chinese resource $key", chinese[key])
            assertTrue("blank Chinese resource $key", chinese.getValue(key).text.isNotBlank())
        }
        val groups = mutableSetOf<String>()
        for (i in 0 until presets.length()) {
            val preset = presets.getJSONObject(i)
            val id = preset.getString("id")
            bothExist("preset_name_$id")
            bothExist("preset_desc_$id")
            groups += preset.optString("group", "Presets")
        }
        for (group in groups) {
            val slug = group.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            bothExist("preset_group_$slug")
        }
    }

    @Test
    fun plainLanguageHelpKeepsItsConciseSummaryAndRicherBody() {
        for (file in listOf("strings_editor.xml")) {
            for (lang in listOf(default(file), chinese(file))) {
                val help = lang.associateBy { it.name }
                for ((key, entry) in help.filterKeys { it.startsWith("help_") && it.endsWith("_summary") }) {
                    val stem = key.removeSuffix("_summary")
                    assertFalse("$file/$key: summary must be one line", entry.text.contains('\n'))
                    assertTrue(
                        "$file/$key: summary should stay a one-liner (<=100 chars)",
                        entry.text.length <= 100,
                    )
                    val body = help.getValue("${stem}_body")
                    assertTrue("$file/$stem: body should be a real explanation", body.text.length >= 80)
                    assertTrue("$file/$stem: body should add more than the summary", body.text.length > entry.text.length)
                }
            }
        }
    }
}
