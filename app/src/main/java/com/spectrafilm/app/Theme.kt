/*
 * Spektrafilm for Android — app theme (Material 3 Expressive). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * One place decides colour, type, motion and shape for every screen. This is a darkroom
 * theme, not a brand-colour swap: near-black and warm-grey surfaces, warm-white text and a
 * single amber action colour. The neutral photo canvases in [SpektraBrand] stay fixed because
 * they are the ground a photograph is judged against.
 *
 * ## Material 3 Expressive
 *
 * `MaterialExpressiveTheme` + `MotionScheme.expressive()`, on `material3:1.5.0-alpha28` pinned
 * above the Compose BOM (owner decision, 2026-09-10). These are `internal` in 1.4.0 — the latest
 * STABLE — and only become public in 1.5.0, which has no beta yet. The motion scheme is the
 * highest-leverage line here: every M3 component reads its spring specs from it, so setting it
 * once changes how the whole app feels without touching a call site.
 *
 * ## Colour
 *
 * The schemes below are deliberately complete rather than the Material baseline: every role
 * the app actually paints with is named, so a component cannot silently fall back to a blue or
 * purple default. Warm grey surfaces and amber stay in one family in both themes; there are no
 * gradients, drifting rainbows or second accent colour. Dark is the primary designed mode; a
 * user's System/Light/Dark choice still decides what the app resolves to.
 *
 * Dynamic colour (Material You, API 31+) remains an opt-in for users who prefer wallpaper-derived
 * chrome, but it now defaults OFF: wallpaper colours around a colour-critical image canvas are a
 * matter of taste, and the built-in darkroom palette is the product identity. A stored explicit
 * choice is always honoured by AppSettings.dynamicColor.
 */
package com.spectrafilm.app

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when the platform can derive a palette from the user's wallpaper (Material You, API 31+). */
internal val dynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Brand surfaces that are deliberately theme-INDEPENDENT: they are the ground a photograph is
 * judged against, so they must not move when the colour scheme does. Values are the ones recorded
 * in the design system's `tokens/colors.css`, which read them out of the source in the first place.
 *
 * These exist so the literals stop being duplicated across Viewer/ToneCurve/Welcome/SpectraIcons.
 */
internal object SpektraBrand {
    /** Editor preview canvas + category bar. */
    val canvasNearBlack = Color(0xFF0B0B0D)

    /** Tone-curve graph canvas. */
    val canvasCurve = Color(0xFF101014)

    /** 100 % crop magnifier well. */
    val canvasMagnifier = Color(0xFF050505)

    /**
     * Deep onboarding backdrop retained for design-token parity. The welcome page now paints on
     * the theme background instead of a fixed near-black gradient, so light mode can stay light.
     */
    val canvasOnboarding = Color(0xFF0A0A12)

    /** Film-strip spectrum, used only as a small static mark. */
    val spectrum = listOf(
        Color(0xFF7B2FF7),
        Color(0xFF2F6BFF),
        Color(0xFF14C7C7),
        Color(0xFF34C759),
        Color(0xFFFFCC00),
        Color(0xFFFF8A00),
        Color(0xFFFF3B30),
    )
}

/**
 * Built-in dark scheme. Warm near-black surfaces, warm-white copy and one amber action colour.
 * The values are explicit so the whole scheme is reviewable in one place.
 */
private fun spektraDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFFD8A75D),
    onPrimary = Color(0xFF261A08),
    primaryContainer = Color(0xFF4A3616),
    onPrimaryContainer = Color(0xFFF6D9A8),
    inversePrimary = Color(0xFF795316),
    secondary = Color(0xFFC8BFB4),
    onSecondary = Color(0xFF2B2620),
    secondaryContainer = Color(0xFF3A352E),
    onSecondaryContainer = Color(0xFFE9E1D7),
    tertiary = Color(0xFFCFC4AE),
    onTertiary = Color(0xFF2E2A21),
    tertiaryContainer = Color(0xFF3D382C),
    onTertiaryContainer = Color(0xFFEDE4D2),
    background = Color(0xFF141311),
    onBackground = Color(0xFFF2EEE7),
    surface = Color(0xFF1B1A18),
    onSurface = Color(0xFFF2EEE7),
    surfaceVariant = Color(0xFF292621),
    onSurfaceVariant = Color(0xFFC8BFB4),
    surfaceTint = Color(0xFFD8A75D),
    inverseSurface = Color(0xFFF2EEE7),
    inverseOnSurface = Color(0xFF24211D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF665E54),
    outlineVariant = Color(0xFF48423A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B3731),
    surfaceDim = Color(0xFF141311),
    surfaceContainer = Color(0xFF201E1B),
    surfaceContainerHigh = Color(0xFF2A2723),
    surfaceContainerHighest = Color(0xFF35312C),
    surfaceContainerLow = Color(0xFF191816),
    surfaceContainerLowest = Color(0xFF0F0E0C),
)

/**
 * Built-in light scheme. Warm paper-like surfaces with the same amber action colour, so a user
 * moving between light and dark sees one product rather than two unrelated themes.
 */
private fun spektraLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF795316),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1DDB8),
    onPrimaryContainer = Color(0xFF2B1D05),
    inversePrimary = Color(0xFFD8A75D),
    secondary = Color(0xFF6C6257),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE4D7),
    onSecondaryContainer = Color(0xFF2C2620),
    tertiary = Color(0xFF6B6048),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E8D5),
    onTertiaryContainer = Color(0xFF2F2A1E),
    background = Color(0xFFFAF7F1),
    onBackground = Color(0xFF211E1A),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF211E1A),
    surfaceVariant = Color(0xFFEEE8DE),
    onSurfaceVariant = Color(0xFF625B52),
    surfaceTint = Color(0xFF795316),
    inverseSurface = Color(0xFF332F2A),
    inverseOnSurface = Color(0xFFF7F2EA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF8B8073),
    outlineVariant = Color(0xFFD3CABD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFDF8),
    surfaceDim = Color(0xFFE5DFD5),
    surfaceContainer = Color(0xFFF0EBE2),
    surfaceContainerHigh = Color(0xFFEAE4DA),
    surfaceContainerHighest = Color(0xFFE3DCD1),
    surfaceContainerLow = Color(0xFFF5F1E9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

/**
 * A compact type scale on the system font. Medium/SemiBold/Bold carry hierarchy without turning
 * screen titles into an English poster; Chinese keeps the platform font so no offline glyph is
 * missing. Body copy stays proportional. Tabular figures are applied only to live numeric
 * readouts (see Widgets.ValuePill), not to prose.
 */
internal val SpektraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
)

/**
 * Shapes describe a photographic tool, not a set of floating pills: 8–12 dp for controls,
 * 14–16 dp for grouped panels, larger only for sheets and dialogs.
 */
internal val SpektraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Wrap the whole app. [dark] comes from the user's [ThemeMode] resolved against the system setting;
 * [dynamicColor] is the user's Material You preference and is ignored below API 31.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpektraTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = when {
        // Capability is checked separately from the preference, so a device that cannot do
        // Material You falls back instead of throwing, and the stored preference survives.
        dynamicColor && dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> spektraDarkScheme()
        else -> spektraLightScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = SpektraShapes,
        typography = SpektraTypography,
        content = content,
    )
}
