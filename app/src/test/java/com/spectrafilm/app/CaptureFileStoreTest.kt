/* Spektrafilm for Android — capture path policy tests. GPLv3. */
package com.spectrafilm.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureFileStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun onlyImmediatePrivateCaptureJpegsAreAccepted() {
        val root = temporary.newFolder("captures")
        val capture = File(root, "capture_test.jpg")
        assertTrue(capture.createNewFile())
        assertTrue(isManagedCaptureFile(root, capture))
    }

    @Test
    fun traversalSubdirectoriesAndUnrelatedFilesAreRejected() {
        val root = temporary.newFolder("captures")
        assertFalse(isManagedCaptureFile(root, File(root, "../outside.jpg")))
        assertFalse(isManagedCaptureFile(root, File(root, "nested/capture_test.jpg")))
        assertFalse(isManagedCaptureFile(root, File(root, "other.jpg")))
        assertFalse(isManagedCaptureFile(root, File(root, "capture_test.png")))
    }

    @Test
    fun symlinkEscapesAreRejected() {
        val root = temporary.newFolder("captures")
        val outside = temporary.newFile("outside.jpg")
        val link = File(root, "capture_link.jpg")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            return
        }
        assertFalse(isManagedCaptureFile(root, link))
    }
}
