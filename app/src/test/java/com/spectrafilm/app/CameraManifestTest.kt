/* Spektrafilm for Android — camera permission and FileProvider boundary tests. GPLv3. */
package com.spectrafilm.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraManifestTest {
    @Test
    fun cameraNeedsOnlyStillCapturePermissionAndNoNetworkOrMicrophone() {
        val document = parse(repoFile("app/src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map {
            permissions.item(it).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue
        }
        assertEquals(1, names.count { it == "android.permission.CAMERA" })
        assertFalse(names.contains("android.permission.INTERNET"))
        assertFalse(names.contains("android.permission.RECORD_AUDIO"))

        val features = document.getElementsByTagName("uses-feature")
        val cameraFeatures = (0 until features.length).map { features.item(it) }.filter {
            it.attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue == "android.hardware.camera.any"
        }
        assertEquals(1, cameraFeatures.size)
        assertEquals("false", cameraFeatures.single().attributes.getNamedItemNS(ANDROID_NS, "required").nodeValue)
    }

    @Test
    fun providerIsPrivateAndOnlyExposesTheCaptureDirectory() {
        val manifest = parse(repoFile("app/src/main/AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) }.single {
            it.attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue == "androidx.core.content.FileProvider"
        }
        assertEquals("false", provider.attributes.getNamedItemNS(ANDROID_NS, "exported").nodeValue)
        assertEquals("true", provider.attributes.getNamedItemNS(ANDROID_NS, "grantUriPermissions").nodeValue)

        val paths = parse(repoFile("app/src/main/res/xml/file_paths.xml"))
        val entries = paths.getElementsByTagName("cache-path")
        assertEquals(1, entries.length)
        val entry = entries.item(0).attributes
        assertEquals("captures", entry.getNamedItem("name").nodeValue)
        assertEquals("captures/", entry.getNamedItem("path").nodeValue)
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
