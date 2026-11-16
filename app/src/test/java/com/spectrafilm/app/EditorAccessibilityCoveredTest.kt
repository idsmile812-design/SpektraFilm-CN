/*
 * Spektrafilm for Android — AppRoot overlay accessibility rule. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The welcome page and the coach marks are Compose overlays composed next to the editor, not
 * platform Dialogs. Without masking, the editor underneath stays in the semantics tree and
 * TalkBack can reach chrome the user cannot see. This pins the pure predicate AppRoot uses to
 * decide when the editor's semantics must be cleared (the overlays' own nodes are untouched).
 */
package com.spectrafilm.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorAccessibilityCoveredTest {

    @Test
    fun welcomeOrCoachOnTheEditorMasksTheEditor() {
        assertTrue(editorAccessibilityCovered(Screen.EDITOR, welcomeVisible = true, coachVisible = false))
        assertTrue(editorAccessibilityCovered(Screen.EDITOR, welcomeVisible = false, coachVisible = true))
    }

    @Test
    fun bareEditorIsNotMasked() {
        assertFalse(editorAccessibilityCovered(Screen.EDITOR, welcomeVisible = false, coachVisible = false))
    }

    @Test
    fun otherDestinationsAreNeverMasked() {
        // The overlays only compose on the editor; a stale flag must not hide Settings/About.
        for (screen in Screen.entries.filter { it != Screen.EDITOR }) {
            assertFalse(
                "$screen must keep its own accessibility tree",
                editorAccessibilityCovered(screen, welcomeVisible = true, coachVisible = true),
            )
        }
    }
}
