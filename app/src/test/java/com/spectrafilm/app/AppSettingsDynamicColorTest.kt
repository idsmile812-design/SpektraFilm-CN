/*
 * Spektrafilm for Android — dynamic-colour preference default. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The darkroom palette is the product identity, so a fresh install starts with Material You OFF.
 * The previous behaviour defaulted ON, and an install that stored that value (or any explicit
 * choice since) must not be silently rewritten: the app reads the existing key and leaves it
 * alone. This pins the pure resolver that AppSettings.dynamicColor delegates to.
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDynamicColorTest {

    @Test
    fun freshInstallDefaultsOff() {
        assertFalse(dynamicColorPreference(null))
    }

    @Test
    fun anExplicitlyStoredTrueIsPreserved() {
        assertTrue(dynamicColorPreference(true))
    }

    @Test
    fun anExplicitlyStoredFalseIsPreserved() {
        assertFalse(dynamicColorPreference(false))
    }
}
