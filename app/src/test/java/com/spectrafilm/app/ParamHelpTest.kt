/*
 * Spektrafilm for Android — unit tests for the plain-language control help (onboarding §6h). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The help content is resource-backed so it localises with the app; the registry itself stays
 * pure data. These tests guard coverage (every opaque control has help) and registration (each
 * field points at a real, distinct string resource) on the plain JVM. A companion resource test
 * checks that the default and Chinese strings are non-empty, concise summaries and richer bodies.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParamHelpTest {

    /** The opaque controls §6h names (couplers/grain/halation/print-gamma) — plus preflash/glare. */
    private val opaqueKeys = listOf(
        ParamHelpText.GRAIN,
        ParamHelpText.HALATION,
        ParamHelpText.COUPLERS,
        ParamHelpText.PRINT_GAMMA,
        ParamHelpText.PREFLASH,
        ParamHelpText.GLARE,
    )

    @Test
    fun everyOpaqueControlHasHelp() {
        for (key in opaqueKeys) {
            val help = ParamHelpText.forKey(key)
            assertNotNull("no help registered for '$key'", help)
            help!!
            assertNotEquals("missing title resource for '$key'", 0, help.titleRes)
            assertNotEquals("missing summary resource for '$key'", 0, help.summaryRes)
            assertNotEquals("missing body resource for '$key'", 0, help.bodyRes)
        }
    }

    @Test
    fun registryHoldsExactlyTheDeclaredKeys() {
        assertEquals(opaqueKeys.toSet(), ParamHelpText.sections.keys)
    }

    @Test
    fun everyFieldHasItsOwnStringResource() {
        val ids = ParamHelpText.sections.values.flatMap { listOf(it.titleRes, it.summaryRes, it.bodyRes) }
        assertEquals("help fields must not share a resource id", ids.size, ids.toSet().size)
    }

    @Test
    fun unknownKeyReturnsNull() {
        assertNull(ParamHelpText.forKey("not-a-control"))
    }
}
