/*
 * Spektrafilm for Android — localized-context activity-result owner regression. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * MainActivity swaps LocalContext for a createConfigurationContext() wrapper so a language
 * change re-resolves strings without recreating the Activity. That wrapper is not an
 * Activity, so ActivityResultRegistryKt can no longer discover an
 * ActivityResultRegistryOwner from LocalContext and every picker/permission launcher in
 * EditorScreen throws:
 *
 *   IllegalStateException: No ActivityResultRegistryOwner was provided via
 *   LocalActivityResultRegistryOwner
 *
 * Compose UI cannot run on the plain JVM and no existing test dependency is added here, so
 * this pins the source structure that keeps the original Activity's registry published.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedContextActivityResultOwnerTest {

    @Test
    fun localizedContextProviderPublishesTheActivityAsActivityResultRegistryOwner() {
        val source = repoFile("app/src/main/java/com/spectrafilm/app/MainActivity.kt").readText()

        assertTrue(
            "MainActivity must import LocalActivityResultRegistryOwner",
            source.lines().any {
                it.trim() == "import androidx.activity.compose.LocalActivityResultRegistryOwner"
            },
        )

        val lines = source.lines()
        val localizedContextLine = lines.indexOfFirst { it.trim() == "LocalContext provides localized," }
        assertTrue("the localized LocalContext provider must still exist", localizedContextLine >= 0)

        // The provider that owns the localized context, from its opening call to its trailing
        // lambda brace, so the assertion cannot be satisfied by an unrelated provider.
        val providerStart = (localizedContextLine downTo 0).first {
            lines[it].contains("CompositionLocalProvider(")
        }
        val providerEnd = (localizedContextLine until lines.size).first {
            lines[it].trimEnd().endsWith(") {")
        }
        val provider = lines.subList(providerStart, providerEnd + 1).map { it.trim() }

        // Keeping the localized context is the reason the owner must be explicit.
        assertTrue(provider.contains("LocalContext provides localized,"))
        assertTrue(provider.contains("LocalResources provides localized.resources,"))
        assertTrue(provider.contains("LocalConfiguration provides localized.resources.configuration,"))
        // Without this the pickers and permission launchers in EditorScreen cannot find an
        // ActivityResultRegistryOwner and MainActivity crashes at startup.
        assertTrue(
            "the localized provider must keep ActivityResultRegistryOwner on the Activity",
            provider.contains("LocalActivityResultRegistryOwner provides this@MainActivity,"),
        )
        assertEquals(
            "the owner must be published by the localized provider itself",
            1,
            provider.count { it == "LocalActivityResultRegistryOwner provides this@MainActivity," },
        )
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
