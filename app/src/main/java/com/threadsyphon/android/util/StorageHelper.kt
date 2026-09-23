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
     * Open DocumentsUI / Files focused on [folder] (not storage root).
     *
     * Strategy (Android 14/15 sideload):
     * 1) Ensure the directory exists.
     * 2) Map absolute path under primary shared storage to a DocumentsProvider
     *    document id `primary:<rel>` and build a **document-under-tree** URI
     *    rooted at `primary:` so DocumentsUI navigates to that document id.
     * 3) Prefer `android.provider.action.BROWSE`, then ACTION_VIEW on that URI,
     *    then OEM/DocumentsUI component launches, then FileProvider directory VIEW.
     *
     * Residual limitation: some OEM "Files" apps ignore deep document ids and
     * still land on volume root; Google DocumentsUI / AOSP Files respect the id.
     * App-private `Android/data/...` trees may be hidden from DocumentsUI entirely.
     */
    fun openFolderInFileManager(context: Context, folder: File): Boolean {
        folder.mkdirs()
        val abs = folder.canonicalFile.absolutePath
        val primaryRoot = Environment.getExternalStorageDirectory().canonicalFile.absolutePath
        val relative = when {
            abs == primaryRoot -> ""
            abs.startsWith("$primaryRoot/") -> abs.removePrefix("$primaryRoot/").trim('/')
            abs.startsWith("/sdcard/") -> abs.removePrefix("/sdcard/").trim('/')
            abs.startsWith("/storage/emulated/0/") -> abs.removePrefix("/storage/emulated/0/").trim('/')
            else -> null
        }

        val intents = mutableListOf<Intent>()
        if (relative != null) {
            val authority = "com.android.externalstorage.documents"
            val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
            // Tree = primary storage root; document = thread folder under that tree.
            val treeRootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
            val folderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, docId)
            val plainDocUri = DocumentsContract.buildDocumentUri(authority, docId)

            fun baseView(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }

            // DocumentsUI browse action — navigates to the given directory document.
            intents += Intent("android.provider.action.BROWSE").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(folderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
            intents += baseView(folderDocUri)
            intents += baseView(plainDocUri)

            // Explicit DocumentsUI / Google Files activities when present.
            for (component in listOf(
                "com.google.android.documentsui/com.android.documentsui.files.FilesActivity",
                "com.android.documentsui/com.android.documentsui.files.FilesActivity",
            )) {
                val parts = component.split('/', limit = 2)
                intents += baseView(folderDocUri).apply {
                    setClassName(parts[0], parts[1])
                }
            }
        }

        // FileProvider content URI for the directory (paths covered in file_paths.xml).
        try {
            val fpUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                folder,
            )
            intents += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fpUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            intents += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fpUri, "resource/folder")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } catch (_: Exception) {
        }

        // Some file managers accept an absolute path extra.
        intents += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("file://${folder.absolutePath}"),
                "resource/folder",
            )
            putExtra("org.openintents.extra.ABSOLUTE_PATH", folder.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        // Last resort: still try starting BROWSE/VIEW without resolve check.
        for (intent in intents.take(3)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
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
