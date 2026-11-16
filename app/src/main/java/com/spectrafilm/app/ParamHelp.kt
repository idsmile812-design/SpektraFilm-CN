/*
 * Spektrafilm for Android — plain-language help for the opaque controls. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The editor exposes the engine's real, physically-named knobs (DIR couplers, halation
 * scatter, density-curve gamma…). They are honest but intimidating. This is the onboarding
 * answer (USER_DRIVEN_SOLUTIONS.md §6h, pain #8): a central registry of friendly,
 * photographer-facing explanations, surfaced by the "?" help sheet on each section header.
 *
 * Relabel-only — the engine still receives the identical params, so this NEVER changes the
 * render and carries zero parity risk. Pure data (no Android/Compose types) so the content
 * coverage is JVM-unit-testable (ParamHelpTest).
 */
package com.spectrafilm.app

import androidx.annotation.StringRes

/**
 * One control's help: a [titleRes], a one-line [summaryRes] (the at-a-glance "what it does")
 * and a fuller [bodyRes] (what it does to the photo + when to reach for it), written for a
 * photographer rather than a film chemist. Resource ids keep the content localisable while the
 * registry itself stays pure data (no Context), so coverage is JVM-unit-testable.
 */
data class ParamHelp(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val bodyRes: Int,
)

/** Central registry of plain-language help, keyed by a stable id (not the display label). */
object ParamHelpText {
    // Stable keys so relabeling a section never breaks the lookup (or its test).
    const val GRAIN = "grain"
    const val HALATION = "halation"
    const val COUPLERS = "couplers"
    const val PRINT_GAMMA = "print_gamma"
    const val PREFLASH = "preflash"
    const val GLARE = "glare"

    val sections: Map<String, ParamHelp> = mapOf(
        GRAIN to ParamHelp(
            titleRes = R.string.help_grain_title,
            summaryRes = R.string.help_grain_summary,
            bodyRes = R.string.help_grain_body,
        ),
        HALATION to ParamHelp(
            titleRes = R.string.help_halation_title,
            summaryRes = R.string.help_halation_summary,
            bodyRes = R.string.help_halation_body,
        ),
        COUPLERS to ParamHelp(
            titleRes = R.string.help_couplers_title,
            summaryRes = R.string.help_couplers_summary,
            bodyRes = R.string.help_couplers_body,
        ),
        PRINT_GAMMA to ParamHelp(
            titleRes = R.string.help_print_gamma_title,
            summaryRes = R.string.help_print_gamma_summary,
            bodyRes = R.string.help_print_gamma_body,
        ),
        PREFLASH to ParamHelp(
            titleRes = R.string.help_preflash_title,
            summaryRes = R.string.help_preflash_summary,
            bodyRes = R.string.help_preflash_body,
        ),
        GLARE to ParamHelp(
            titleRes = R.string.help_glare_title,
            summaryRes = R.string.help_glare_summary,
            bodyRes = R.string.help_glare_body,
        ),
    )

    /** The help for [key], or null if none is registered. */
    fun forKey(key: String): ParamHelp? = sections[key]
}
