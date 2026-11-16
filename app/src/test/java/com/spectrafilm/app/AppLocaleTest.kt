/*
 * Spektrafilm for Android — in-app language resolution. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Pure logic, so the "Simplified Chinese system gets Chinese, everything else defaults to
 * English, and an explicit choice always wins" rule is checkable on the plain JVM without a
 * Context or the Android resource system.
 */
package com.spectrafilm.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocaleTest {

    @Test
    fun simplifiedChineseSystemDefaultsToChinese() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale(AppLanguage.SYSTEM, Locale.forLanguageTag("zh-Hans-SG")),
        )
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale(AppLanguage.SYSTEM, Locale.forLanguageTag("zh")),
        )
    }

    @Test
    fun everyOtherSystemDefaultsToEnglish() {
        for (tag in listOf("en-US", "de-DE", "ja-JP", "fr-FR", "zh-TW", "zh-HK", "zh-Hant-CN")) {
            assertEquals(
                "system $tag should default to English",
                Locale.ENGLISH,
                resolveAppLocale(AppLanguage.SYSTEM, Locale.forLanguageTag(tag)),
            )
        }
    }

    @Test
    fun explicitChoicesWinOverTheSystem() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale(AppLanguage.SIMPLIFIED_CHINESE, Locale.US),
        )
        assertEquals(
            Locale.ENGLISH,
            resolveAppLocale(AppLanguage.ENGLISH, Locale.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun everyLanguageChoiceHasItsOwnLabelResource() {
        val ids = AppLanguage.entries.map { it.labelRes }
        assertEquals(3, ids.size)
        assertTrue(ids.all { it != 0 })
        assertEquals("language labels must be distinct resources", ids.size, ids.toSet().size)
    }

    @Test
    fun simplifiedChineseDetectionDistinguishesTheScripts() {
        assertTrue(isSimplifiedChinese(Locale.SIMPLIFIED_CHINESE))
        assertTrue(isSimplifiedChinese(Locale.forLanguageTag("zh-Hans-TW")))
        assertFalse(isSimplifiedChinese(Locale.forLanguageTag("zh-Hant-CN")))
        assertFalse(isSimplifiedChinese(Locale.forLanguageTag("zh-TW")))
        assertFalse(isSimplifiedChinese(Locale.US))
        assertFalse(isSimplifiedChinese(Locale.JAPAN))
    }
}
