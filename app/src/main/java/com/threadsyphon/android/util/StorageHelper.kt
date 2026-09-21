package com.threadsyphon.android.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.threadsyphon.android.data.model.Constants
import com.threadsyphon.android.data.model.DownloadLocation
import java.io.File
import java.io.FileInputStream

object StorageHelper {
    fun appExternalRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "threadsyphon").also { it.mkdirs() }
    }

    fun threadFolder(
        context: Context,
        board: String,
        threadNo: Long,
        location: DownloadLocation,
    ): File {
        val root = when (location) {
            DownloadLocation.AppExternal -> appExternalRoot(context)
            DownloadLocation.MediaStoreDownloads -> {
                // Stage under app external; MediaStore copy happens after download.
                File(appExternalRoot(context), "staging")
            }
        }
        return File(root, "$board/$threadNo").also { it.mkdirs() }
    }

    fun freeBytes(path: File): Long {
        return try {
            val stat = StatFs(path.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun isLowStorage(path: File, needed: Long = Constants.FREE_SPACE_RESERVE_BYTES): Boolean {
        return freeBytes(path) < needed + Constants.FREE_SPACE_RESERVE_BYTES
    }

    /**
     * Publish a finished file into Downloads/threadsyphon via MediaStore (API 29+ friendly).
     */
    fun publishToDownloads(
        context: Context,
        source: File,
        board: String,
        threadNo: Long,
        displayName: String,
        mime: String,
    ): Boolean {
        return try {
            val relative = "Download/threadsyphon/$board/$threadNo"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        ".jpg", ".jpeg" -> "image/jpeg"
        ".png" -> "image/png"
        ".gif" -> "image/gif"
        ".webp" -> "image/webp"
        ".webm" -> "video/webm"
        ".mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun publicDownloadsHint(): String =
        Environment.DIRECTORY_DOWNLOADS + "/threadsyphon"
}
