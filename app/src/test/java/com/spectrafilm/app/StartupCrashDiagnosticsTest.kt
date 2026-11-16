/*
 * Spektrafilm for Android — startup crash recovery tests. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The Android startup lifecycle cannot run on the plain JVM, so these tests pin the two
 * pieces that can regress without a device: the crash-record handoff from the dying process
 * to the next launch, and the source ordering that installs the handler before any language
 * or settings work. Redaction and byte bounds are reused from DiagnosticsTest.
 */
package com.spectrafilm.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCrashDiagnosticsTest {

    @Test
    fun crashRecordWrittenBeforeProcessDeathIsReadableOnTheNextLaunch() {
        val now = 2_000_000_000_000L
        val file = tempFile()

        Diagnostics.writeCrashRecord(
            file = file,
            timestamp = "2026-01-02 03:04:05",
            threadName = "main",
            stackTrace = "java.lang.IllegalStateException: startup exploded\n" +
                "\tat com.spectrafilm.app.MainActivity.onCreate(MainActivity.kt:1)",
        )
        file.setLastModified(now)

        val firstLaunch = Diagnostics.readPersistedCrash(file, now)
        val secondLaunch = Diagnostics.readPersistedCrash(file, now)

        assertNotNull(firstLaunch)
        assertTrue(firstLaunch!!.startsWith(Diagnostics.CRASH_FORMAT))
        assertTrue(firstLaunch.contains("startup exploded"))
        assertTrue(firstLaunch.contains("MainActivity.kt:1"))
        assertEquals(firstLaunch, secondLaunch)
        // Reading is not consuming: the record stays available to the diagnostics page.
        assertTrue(file.isFile)
    }

    @Test
    fun startupReportCarriesOnlyTheHeaderAndRedactedCrashRecord() {
        val report = Diagnostics.startupReportText(
            header = "app: 0.10.0 (12)\ndevice: Pixel; Android 14 (API 34)\n\n",
            crashSection = "--- last crash ---\n" +
                "java.lang.RuntimeException: startup exploded\n" +
                "\tat com.spectrafilm.app.MainActivity.onCreate(MainActivity.kt:1)\n" +
                "path=/data/user/0/com.spectrafilm.app/files/private\n" +
                "uri=content://media/external/images/media/42\n",
        )

        assertTrue(report.startsWith("app: 0.10.0 (12)"))
        assertTrue(report.contains("java.lang.RuntimeException: startup exploded"))
        assertTrue(report.contains("MainActivity.kt:1"))
        assertFalse(report.contains("/data/user"))
        assertFalse(report.contains("content://"))
        // The startup report never asks the engine, memory status or logcat for evidence.
        assertFalse(report.contains("logcat"))
        assertFalse(report.contains("VmRSS"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= Diagnostics.MAX_REPORT_BYTES)
    }

    @Test
    fun activityInstallsTheHandlerBeforeLanguageWorkAndReadsTheCrashBeforeSettings() {
        val source = repoFile("app/src/main/java/com/spectrafilm/app/MainActivity.kt").readText()

        val attachBody = source
            .substringAfter("override fun attachBaseContext(newBase: Context)")
            .substringBefore("override fun onNewIntent")
        val install = attachBody.indexOf("Diagnostics.installCrashHandler(newBase)")
        val localized = attachBody.indexOf("localizedContext(newBase")
        assertTrue("attachBaseContext must install the crash handler", install >= 0)
        assertTrue("attachBaseContext must resolve the locale", localized >= 0)
        assertTrue(
            "installCrashHandler(newBase) must be the first step, before localizedContext",
            install < localized,
        )

        val onCreateBody = source
            .substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("private fun AppRoot")
        val crashCheck = onCreateBody.indexOf("Diagnostics.lastCrash(this)")
        val settings = onCreateBody.indexOf("val settings = AppSettings.from(this)")
        val appRoot = onCreateBody.indexOf("AppRoot(")
        assertTrue(
            "onCreate must keep the idempotent handler install",
            onCreateBody.contains("Diagnostics.installCrashHandler(this)"),
        )
        assertTrue("onCreate must check lastCrash", crashCheck >= 0)
        assertTrue("lastCrash must be checked before settings", crashCheck < settings)
        assertTrue("lastCrash must be checked before AppRoot", crashCheck < appRoot)
    }

    private fun tempFile(): File =
        Files.createTempFile("spectrafilm-startup-crash-", ".txt").toFile().apply {
            deleteOnExit()
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
