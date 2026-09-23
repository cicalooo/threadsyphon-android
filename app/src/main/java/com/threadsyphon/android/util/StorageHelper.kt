package com.threadsyphon.android.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.data.model.Constants
import com.threadsyphon.android.data.model.DownloadLocation
import java.io.File
import java.io.FileInputStream

object StorageHelper {
    fun appExternalRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, Constants.SHARED_ROOT_FOLDER).also { it.mkdirs() }
    }

    /** Visible shared storage: Internal storage/threadsyphon */
    fun sharedRoot(): File {
        val base = Environment.getExternalStorageDirectory()
        return File(base, Constants.SHARED_ROOT_FOLDER).also { it.mkdirs() }
    }

    fun customRoot(path: String): File? {
        if (path.isBlank()) return null
        return File(path).also { it.mkdirs() }
    }

    fun resolveRoot(context: Context, settings: AppSettings): File {
        return when (settings.downloadLocation) {
            DownloadLocation.SharedRoot -> sharedRoot()
            DownloadLocation.CustomPath -> customRoot(settings.customRootPath) ?: sharedRoot()
            DownloadLocation.AppExternal -> appExternalRoot(context)
            DownloadLocation.MediaStoreDownloads -> File(appExternalRoot(context), "staging").also { it.mkdirs() }
        }
    }

    fun threadFolder(
        context: Context,
        board: String,
        threadNo: Long,
        settings: AppSettings,
    ): File {
        val root = resolveRoot(context, settings)
        return File(root, "$board/$threadNo").also { it.mkdirs() }
    }

    @Deprecated("Use overload with AppSettings", ReplaceWith("threadFolder(context, board, threadNo, settings)"))
    fun threadFolder(
        context: Context,
        board: String,
        threadNo: Long,
        location: DownloadLocation,
    ): File {
        val settings = AppSettings(downloadLocation = location)
        return threadFolder(context, board, threadNo, settings)
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

    fun publishToDownloads(
        context: Context,
        source: File,
        board: String,
        threadNo: Long,
        displayName: String,
        mime: String,
    ): Boolean {
        return try {
            val relative = "Download/${Constants.SHARED_ROOT_FOLDER}/$board/$threadNo"
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
        Environment.DIRECTORY_DOWNLOADS + "/${Constants.SHARED_ROOT_FOLDER}"

    fun sharedRootHint(): String = "Internal storage/${Constants.SHARED_ROOT_FOLDER}"

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun allFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * Open the system file manager / DocumentsUI at [folder].
     */
    fun openFolderInFileManager(context: Context, folder: File): Boolean {
        folder.mkdirs()
        val abs = folder.absolutePath
        // primary:threadsyphon/board/no  style document URI
        val relative = abs
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .trim('/')
        val docId = "primary:$relative"
        val docUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId,
        )
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW).apply {
                @Suppress("DEPRECATION")
                setDataAndType(Uri.fromFile(folder), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.intent.action.VIEW_DOWNLOADS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        // Last resort: browse tree root
        return try {
            val tree = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                docId,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pathFromTreeUri(uri: Uri): String? {
        // content://com.android.externalstorage.documents/tree/primary%3Athreadsyphon
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":", limit = 2)
        if (split.size < 2) return null
        val volume = split[0]
        val rel = split[1]
        return if (volume.equals("primary", ignoreCase = true)) {
            File(Environment.getExternalStorageDirectory(), rel).absolutePath
        } else {
            // /storage/<uuid>/<rel>
            File("/storage/$volume/$rel").absolutePath
        }
    }
}
