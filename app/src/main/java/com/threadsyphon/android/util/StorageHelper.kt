package com.threadsyphon.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

data class OpenFolderOutcome(
    val opened: Boolean,
    val folder: File,
    /** True when shared Internal storage/threadsyphon is desired but MANAGE_EXTERNAL_STORAGE is missing. */
    val needsAllFilesAccess: Boolean,
    val usedAppPrivateFallback: Boolean,
)

object StorageHelper {
    fun appExternalRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, Constants.SHARED_ROOT_FOLDER).also { it.mkdirs() }
    }

    /** Visible shared storage: Internal storage/threadsyphon */
    fun sharedRoot(): File {
        val base = Environment.getExternalStorageDirectory()
        return File(base, Constants.SHARED_ROOT_FOLDER)
    }

    fun customRoot(path: String): File? {
        if (path.isBlank()) return null
        return File(path)
    }

    /** Probe whether [dir] exists or can be created and written. */
    fun ensureWritableDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val probe = File(dir, ".ts_write_probe")
            probe.outputStream().use { it.write(1) }
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isUnderAppPrivate(context: Context, folder: File): Boolean {
        val app = (context.getExternalFilesDir(null) ?: context.filesDir).canonicalFile.absolutePath
        return try {
            folder.canonicalFile.absolutePath.startsWith("$app/") ||
                folder.canonicalFile.absolutePath == app
        } catch (_: Exception) {
            folder.absolutePath.contains("/Android/data/${context.packageName}/")
        }
    }

    /**
     * Resolve download root.
     * SharedRoot prefers visible `/storage/emulated/0/threadsyphon` when writable
     * (all-files access or successful mkdirs). Falls back to app-external only when
     * shared storage is not writable so downloads still succeed.
     */
    fun resolveRoot(context: Context, settings: AppSettings): File {
        return when (settings.downloadLocation) {
            DownloadLocation.SharedRoot -> {
                val shared = sharedRoot()
                if (ensureWritableDir(shared)) shared
                else appExternalRoot(context)
            }
            DownloadLocation.CustomPath -> {
                val custom = customRoot(settings.customRootPath)
                when {
                    custom != null && ensureWritableDir(custom) -> custom
                    ensureWritableDir(sharedRoot()) -> sharedRoot()
                    else -> appExternalRoot(context)
                }
            }
            DownloadLocation.AppExternal -> appExternalRoot(context)
            DownloadLocation.MediaStoreDownloads ->
                File(appExternalRoot(context), "staging").also { it.mkdirs() }
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

    /** Preferred visible shared folder for a thread (may not be writable yet). */
    fun sharedThreadFolder(board: String, threadNo: Long): File =
        File(sharedRoot(), "$board/$threadNo")

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
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun copyPathToClipboard(context: Context, path: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("folder", path))
    }

    /**
     * Choose the best folder to open for a thread and launch a file manager.
     *
     * Prefer shared Internal storage/threadsyphon when settings ask for SharedRoot
     * (including after legacy AppExternal migration). Create the directory first.
     * If shared is not writable / all-files missing, still try hard to open whatever
     * dir exists, and report [OpenFolderOutcome.needsAllFilesAccess].
     */
    fun openThreadFolder(
        context: Context,
        board: String,
        threadNo: Long,
        settings: AppSettings,
    ): OpenFolderOutcome {
        val wantsShared = settings.downloadLocation == DownloadLocation.SharedRoot ||
            settings.downloadLocation == DownloadLocation.AppExternal
        val sharedFolder = sharedThreadFolder(board, threadNo)
        val configured = threadFolder(context, board, threadNo, settings)
        val needsAllFiles = wantsShared &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !hasAllFilesAccess()

        val folder = when {
            wantsShared && ensureWritableDir(sharedFolder) -> sharedFolder
            wantsShared && sharedFolder.exists() -> sharedFolder
            else -> configured.also { it.mkdirs() }
        }
        // Always ensure target exists before launching Files.
        folder.mkdirs()

        val opened = openFolderInFileManager(context, folder)
        val usedPrivate = isUnderAppPrivate(context, folder)
        return OpenFolderOutcome(
            opened = opened,
            folder = folder,
            needsAllFilesAccess = needsAllFiles || (wantsShared && usedPrivate),
            usedAppPrivateFallback = usedPrivate && wantsShared,
        )
    }

    /**
     * Open DocumentsUI / Files focused on [folder] (not storage root).
     *
     * Strategy (Android 14/15 OEM):
     * 1) Ensure the directory exists.
     * 2) Map path under primary shared storage to DocumentsProvider id `primary:<rel>`.
     * 3) Try BROWSE / VIEW with document-under-tree and plain document URIs,
     *    including explicit DocumentsUI / Google Files / OEM My Files components.
     * 4) Try FileProvider directory VIEW + common path extras.
     * 5) Start activities even when resolveActivity is null (package visibility),
     *    catching ActivityNotFoundException.
     *
     * App-private `Android/data/...` trees are often hidden from DocumentsUI —
     * callers should prefer [openThreadFolder] which migrates toward shared root.
     */
    fun openFolderInFileManager(context: Context, folder: File): Boolean {
        folder.mkdirs()
        val abs = try {
            folder.canonicalFile.absolutePath
        } catch (_: Exception) {
            folder.absolutePath
        }
        val primaryRoot = try {
            Environment.getExternalStorageDirectory().canonicalFile.absolutePath
        } catch (_: Exception) {
            Environment.getExternalStorageDirectory().absolutePath
        }
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
            val treeRootUri = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
            val folderDocUri = DocumentsContract.buildDocumentUriUsingTree(treeRootUri, docId)
            val plainDocUri = DocumentsContract.buildDocumentUri(authority, docId)
            // Also try a tree URI rooted at the folder itself (some OEM Files apps).
            val folderTreeUri = DocumentsContract.buildTreeDocumentUri(authority, docId)

            fun baseView(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }

            fun browse(uri: Uri): Intent = Intent("android.provider.action.BROWSE").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }

            intents += browse(folderDocUri)
            intents += browse(plainDocUri)
            intents += baseView(folderDocUri)
            intents += baseView(plainDocUri)
            intents += baseView(folderTreeUri)

            // Explicit DocumentsUI / Google Files / OEM file manager activities.
            val components = listOf(
                "com.google.android.documentsui/com.android.documentsui.files.FilesActivity",
                "com.android.documentsui/com.android.documentsui.files.FilesActivity",
                "com.google.android.apps.nbu.files/com.google.android.apps.nbu.files.home.HomeActivity",
                "com.sec.android.app.myfiles/com.sec.android.app.myfiles.external.ui.MainActivity",
                "com.mi.android.globalFileexplorer/com.android.fileexplorer.FileExplorerTabActivity",
                "com.android.fileexplorer/com.android.fileexplorer.FileExplorerTabActivity",
            )
            for (component in components) {
                val parts = component.split('/', limit = 2)
                intents += baseView(folderDocUri).apply { setClassName(parts[0], parts[1]) }
                intents += browse(folderDocUri).apply { setClassName(parts[0], parts[1]) }
                intents += baseView(plainDocUri).apply { setClassName(parts[0], parts[1]) }
            }

            // content://…/document/primary%3Athreadsyphon%2Fboard%2Ftid (explicit string form)
            val encodedDoc = Uri.parse(
                "content://$authority/document/" + Uri.encode(docId),
            )
            intents += baseView(encodedDoc)
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
            intents += Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fpUri, "*/*")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } catch (_: Exception) {
        }

        // Path-based extras used by Solid Explorer, FX, Amaze, Mi/Samsung My Files, etc.
        val fileUri = Uri.parse("file://${folder.absolutePath}")
        intents += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "resource/folder")
            putExtra("org.openintents.extra.ABSOLUTE_PATH", folder.absolutePath)
            putExtra("com.sec.android.app.myfiles.PICK_DATA", folder.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        intents += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, DocumentsContract.Document.MIME_TYPE_DIR)
            putExtra("org.openintents.extra.ABSOLUTE_PATH", folder.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Samsung My Files browse-by-path
        intents += Intent("com.sec.android.app.myfiles.VIEW").apply {
            putExtra("FOLDERPATH", folder.absolutePath)
            putExtra("com.sec.android.app.myfiles.PICK_DATA", folder.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 1) Prefer intents that resolve (with queries declared in manifest).
        for (intent in intents) {
            if (tryStart(context, intent, requireResolved = true)) return true
        }
        // 2) Blind start — package visibility may hide resolveActivity results.
        for (intent in intents) {
            if (tryStart(context, intent, requireResolved = false)) return true
        }
        return false
    }

    private fun tryStart(context: Context, intent: Intent, requireResolved: Boolean): Boolean {
        return try {
            if (requireResolved) {
                val pm = context.packageManager
                val resolved = if (Build.VERSION.SDK_INT >= 33) {
                    pm.resolveActivity(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }
                if (resolved == null) return false
            }
            context.startActivity(intent)
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
