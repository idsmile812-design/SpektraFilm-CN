/*
 * Spektrafilm for Android — in-app language selection. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The app ships English (default resources) and Simplified Chinese (values-zh-rCN).
 * [AppLanguage.SYSTEM] resolves to Simplified Chinese only on a Simplified Chinese
 * system and to English everywhere else; the explicit choices always win. The locale
 * is applied to a context configuration rather than the process-wide default, so the
 * rest of the process (and any system UI) keeps the user's own locale.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/** Persisted language choice. Labels are resolved at display time (the labels themselves localise). */
enum class AppLanguage(@StringRes val labelRes: Int) {
    SYSTEM(R.string.screen_settings_language_system),
    SIMPLIFIED_CHINESE(R.string.screen_settings_language_zh_cn),
    ENGLISH(R.string.screen_settings_language_en),
}

/**
 * True when [locale] is a Simplified Chinese locale. Traditional Chinese systems
 * (zh-TW / zh-HK / zh-MO without an explicit Hans script) default to English, as the
 * product spec requires: only a Simplified Chinese system gets Chinese by default.
 */
internal fun isSimplifiedChinese(locale: Locale): Boolean {
    if (!locale.language.equals("zh", ignoreCase = true)) return false
    val script = locale.script
    if (script.isNotEmpty()) return script.equals("Hans", ignoreCase = true)
    return when (locale.country.uppercase(Locale.ROOT)) {
        "CN", "SG", "MY", "" -> true
        else -> false
    }
}

/** Resolve the app locale for [selection] against the device's [systemLocale]. */
internal fun resolveAppLocale(selection: AppLanguage, systemLocale: Locale): Locale = when {
    selection == AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    selection == AppLanguage.ENGLISH -> Locale.ENGLISH
    isSimplifiedChinese(systemLocale) -> Locale.SIMPLIFIED_CHINESE
    else -> Locale.ENGLISH
}

/**
 * A [base] context whose resources are resolved in the selected app locale.
 *
 * Works from API 24 (the app's minSdk): [Configuration.setLocale] and
 * [Context.createConfigurationContext] are both available well below it.
 */
internal fun localizedContext(base: Context, selection: AppLanguage): Context {
    val locale = resolveAppLocale(selection, Locale.getDefault())
    val configuration = Configuration(base.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return base.createConfigurationContext(configuration)
}
