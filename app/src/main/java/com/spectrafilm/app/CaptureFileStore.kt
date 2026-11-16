/* Spektrafilm for Android — private capture files. GPLv3. */
package com.spectrafilm.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

internal fun isManagedCaptureFile(root: File, candidate: File): Boolean = runCatching {
    val canonicalRoot = root.canonicalFile
    val canonical = candidate.canonicalFile
    canonical.parentFile == canonicalRoot &&
        canonical.name.startsWith("capture_") &&
        canonical.name.endsWith(".jpg", ignoreCase = true)
}.getOrDefault(false)

internal class CaptureFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, "captures")

    fun newFile(): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create private capture directory")
        }
        return File(directory, "capture_${UUID.randomUUID()}.jpg")
    }

    fun uriFor(file: File): Uri {
        require(isManagedCaptureFile(directory, file) && file.isFile) {
            "capture is missing or outside the private capture directory"
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.files",
            file,
        )
    }

    fun discard(file: File) {
        if (!isManagedCaptureFile(directory, file)) return
        if (file.exists() && !file.delete()) {
            throw IOException("could not delete discarded capture")
        }
    }
}
