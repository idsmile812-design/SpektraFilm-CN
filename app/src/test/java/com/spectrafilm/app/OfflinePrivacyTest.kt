/*
 * Spektrafilm for Android — offline/privacy boundary. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * This Chinese edition is fully local: no in-app HTTP client, no INTERNET permission,
 * no updater, account, analytics, advertising, API key, photo upload, or network SDK.
 * These tests inspect repository files directly (the same plain-JUnit style as the
 * backup/locale suites) so they keep guarding the policy without any new framework.
 */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePrivacyTest {

    @Test
    fun manifestHasNoInternetPermissionOrNetworkConfig() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()

        assertFalse(
            "INTERNET permission must stay absent in the fully offline edition",
            manifest.contains("android.permission.INTERNET"),
        )
        assertFalse(
            "a network security config would only exist for an in-app HTTP client",
            manifest.contains("networkSecurityConfig"),
        )
        assertFalse(repoFile("app/src/main/res/xml/network_security_config.xml").exists())
    }

    @Test
    fun manifestKeepsTheForegroundExportPermissions() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()

        for (required in listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        )) {
            assertTrue("missing required export permission $required", manifest.contains(required))
        }
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""))
    }

    @Test
    fun mainSourcesContainNoUpdaterHttpClientOrUploadPath() {
        val sources = sourceFiles()
        val forbidden = listOf(
            "AppUpdater",
            "UpdateInfo",
            "HttpURLConnection",
            "java.net.URL",
            "openConnection(",
            "okhttp3",
            "retrofit2",
            "MultipartBody",
            "RequestBody",
            "multipart/form-data",
            "uploadPhoto",
        )

        for (file in sources) {
            val text = file.readText()
            for (token in forbidden) {
                assertFalse(
                    "${file.relativeTo(projectRoot()).path} must not contain $token",
                    text.contains(token),
                )
            }
        }
        assertFalse(repoFile("app/src/main/java/com/spectrafilm/app/AppUpdater.kt").exists())
        assertFalse(repoFile("app/src/test/java/com/spectrafilm/app/AppUpdaterTest.kt").exists())
    }

    @Test
    fun noAccountAnalyticsAdvertisingOrApiKeyDependencyExists() {
        val manifests = listOf(
            repoFile("app/build.gradle.kts").readText(),
            repoFile("gradle/libs.versions.toml").readText(),
        ).joinToString("\n").lowercase()
        val forbiddenDependencies = listOf(
            "firebase",
            "play-services-auth",
            "play-services-ads",
            "applovin",
            "admob",
            "crashlytics",
            "okhttp",
            "retrofit",
            "volley",
        )
        for (token in forbiddenDependencies) {
            assertFalse("unexpected dependency token $token", manifests.contains(token))
        }

        val source = sourceFiles().joinToString("\n") { it.readText() }
        for (token in listOf(
            "FirebaseAnalytics",
            "FirebaseAuth",
            "GoogleSignIn",
            "MobileAds",
            "AppLovin",
            "Crashlytics",
        )) {
            assertFalse("unexpected account/analytics/advertising SDK $token", source.contains(token))
        }
        val apiKeyLike = Regex("""AIza[0-9A-Za-z_-]{35}""")
        assertFalse("hard-coded Google API key", apiKeyLike.containsMatchIn(source))
    }

    @Test
    fun externalLinksAreOnlyBrowserHandoffs() {
        val links = repoFile("app/src/main/java/com/spectrafilm/app/Links.kt").readText()

        assertTrue("links must be opened by the browser", links.contains("Intent.ACTION_VIEW"))
        assertFalse("Links must not perform in-app networking", links.contains("openConnection("))
        assertFalse(links.contains("HttpURLConnection"))
    }

    private fun sourceFiles(): List<File> =
        repoFile("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repoFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
