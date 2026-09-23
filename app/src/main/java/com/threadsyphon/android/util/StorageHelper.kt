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
    /** Files copied/moved from app-private staging into the shared thread folder. */
    val migratedPrivateFiles: Int = 0,
    /** Old media still only under Android/data staging (could not copy yet). */
    val hadPrivateStagingOnly: Boolean = false,
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
     *
     * MediaStoreDownloads uses shared root as the working tree when writable
     * (MediaStore publish still copies into Download/threadsyphon). Never use
     * app-private `staging/` as the primary resolve root when shared is available.
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
            DownloadLocation.MediaStoreDownloads -> {
                val shared = sharedRoot()
                if (ensureWritableDir(shared)) shared
                else File(appExternalRoot(context), "staging").also { it.mkdirs() }
            }
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

    /** App-private dirs that older builds used for MediaStore staging / AppExternal. */
    fun privateThreadCandidates(context: Context, board: String, threadNo: Long): List<File> {
        val appRoot = appExternalRoot(context)
        return listOf(
            File(appRoot, "staging/$board/$threadNo"),
            File(appRoot, "$board/$threadNo"),
        )
    }

    fun privateThreadHasMedia(context: Context, board: String, threadNo: Long): Boolean {
        return privateThreadCandidates(context, board, threadNo).any { dir ->
            dir.isDirectory && dir.listFiles()?.any { it.isFile && !it.name.endsWith(".part") } == true
        }
    }

    /**
     * Copy/move leftover app-private thread media into [dest] (shared folder).
     * @return number of files newly placed in [dest]
     */
    fun migratePrivateThreadMediaToShared(
        context: Context,
        board: String,
        threadNo: Long,
        dest: File,
    ): Int {
        if (!ensureWritableDir(dest)) return 0
        var moved = 0
        for (src in privateThreadCandidates(context, board, threadNo)) {
            if (!src.isDirectory) continue
            val files = src.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || f.name.endsWith(".part")) continue
                val target = File(dest, f.name)
                try {
                    if (target.exists() && target.length() == f.length()) {
                        f.delete()
                        continue
                    }
                    f.copyTo(target, overwrite = true)
                    if (target.exists() && target.length() == f.length()) {
                        f.delete()
                        moved++
                    }
                } catch (_: Exception) {
                }
            }
        }
        return moved
    }

    /**
     * Open the shared Internal storage/threadsyphon/<board>/<tid> folder in a file manager.
     *
     * Never hands FileProvider content URIs to external managers (they cannot browse
     * Android/data). Without all-files access on API 30+, does not pretend open worked —
     * returns [OpenFolderOutcome.needsAllFilesAccess] so UI can launch settings and copy
     * the filesystem path.
     */
    fun openThreadFolder(
        context: Context,
        board: String,
        threadNo: Long,
        settings: AppSettings,
    ): OpenFolderOutcome {
        val sharedFolder = sharedThreadFolder(board, threadNo)
        val custom = if (settings.downloadLocation == DownloadLocation.CustomPath) {
            customRoot(settings.customRootPath)?.let { File(it, "$board/$threadNo") }
        } else {
            null
        }
        // Always prefer shared path for Open folder (CustomPath opens the custom tree).
        val targetFolder = custom ?: sharedFolder
        val needsAllFiles = (custom == null) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !hasAllFilesAccess()

        if (needsAllFiles) {
            val hadPrivate = privateThreadHasMedia(context, board, threadNo)
            return OpenFolderOutcome(
                opened = false,
                folder = sharedFolder,
                needsAllFilesAccess = true,
                usedAppPrivateFallback = false,
                migratedPrivateFiles = 0,
                hadPrivateStagingOnly = hadPrivate,
            )
        }

        // Create shared (or custom) dirs now that we can write.
        targetFolder.mkdirs()
        ensureWritableDir(targetFolder)

        val migrated = if (custom == null) {
            migratePrivateThreadMediaToShared(context, board, threadNo, sharedFolder)
        } else {
            0
        }
        val stillPrivateOnly = custom == null &&
            migrated == 0 &&
            privateThreadHasMedia(context, board, threadNo) &&
            (targetFolder.listFiles()?.none { it.isFile } != false)

        val opened = openFolderInFileManager(context, targetFolder)
        return OpenFolderOutcome(
            opened = opened,
            folder = targetFolder,
            needsAllFilesAccess = false,
            usedAppPrivateFallback = false,
            migratedPrivateFiles = migrated,
            hadPrivateStagingOnly = stillPrivateOnly,
        )
    }

    /**
     * Open DocumentsUI / Files focused on [folder] (not storage root).
     *
     * Strategy:
     * 1) Ensure the directory exists.
     * 2) Map path under primary shared storage to DocumentsProvider id `primary:<rel>`.
     * 3) Try BROWSE / VIEW with document-under-tree (tree `primary:threadsyphon` or `primary:`)
     *    and plain document URIs + EXTRA_INITIAL_URI; Material Files via file:// path.
     * 4) Path extras for OEM managers (still no FileProvider).
     * 5) Stock Files at volume root only as last fallback (OEM residual limit).
     *
     * **Never** hands `content://…fileprovider…` to external file managers.
     */
    fun openFolderInFileManager(context: Context, folder: File): Boolean {
        folder.mkdirs()
        val abs = try {
            folder.canonicalFile.absolutePath
        } catch (_: Exception) {
            folder.absolutePath
        }
        // Refuse to launch FileProvider / Android/data browse — callers must use shared path.
        if (abs.contains("/Android/data/") || abs.contains("/Android/obb/")) {
            return false
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

        val folderFocused = mutableListOf<Intent>()
        val rootFallback = mutableListOf<Intent>()

        if (relative != null) {
            val authority = "com.android.externalstorage.documents"
            val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
            val treePrimary = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
            val treeThreadsyphon = DocumentsContract.buildTreeDocumentUri(
                authority,
                "primary:${Constants.SHARED_ROOT_FOLDER}",
            )
            val folderDocUnderPrimary = DocumentsContract.buildDocumentUriUsingTree(treePrimary, docId)
            val folderDocUnderTs = DocumentsContract.buildDocumentUriUsingTree(treeThreadsyphon, docId)
            val plainDocUri = DocumentsContract.buildDocumentUri(authority, docId)
            val folderTreeUri = DocumentsContract.buildTreeDocumentUri(authority, docId)
            val rootDocUri = DocumentsContract.buildDocumentUri(authority, "primary:")

            fun baseView(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addCategory(Intent.CATEGORY_DEFAULT)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }

            fun browse(uri: Uri): Intent = Intent("android.provider.action.BROWSE").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }

            // Prefer folder-focused document URIs first.
            folderFocused += browse(folderDocUnderTs)
            folderFocused += browse(folderDocUnderPrimary)
            folderFocused += browse(plainDocUri)
            folderFocused += baseView(folderDocUnderTs)
            folderFocused += baseView(folderDocUnderPrimary)
            folderFocused += baseView(plainDocUri)
            folderFocused += baseView(folderTreeUri)

            val components = listOf(
                "com.google.android.documentsui/com.android.documentsui.files.FilesActivity",
                "com.android.documentsui/com.android.documentsui.files.FilesActivity",
                "com.google.android.apps.nbu.files/com.google.android.apps.nbu.files.home.HomeActivity",
                "com.sec.android.app.myfiles/com.sec.android.app.myfiles.external.ui.MainActivity",
                "com.mi.android.globalFileexplorer/com.android.fileexplorer.FileExplorerTabActivity",
                "com.android.fileexplorer/com.android.fileexplorer.FileExplorerTabActivity",
                "me.zhanghai.android.files/me.zhanghai.android.files.filelist.FileListActivity",
            )
            for (component in components) {
                val parts = component.split('/', limit = 2)
                folderFocused += baseView(plainDocUri).apply { setClassName(parts[0], parts[1]) }
                folderFocused += browse(plainDocUri).apply { setClassName(parts[0], parts[1]) }
                folderFocused += baseView(folderDocUnderTs).apply { setClassName(parts[0], parts[1]) }
                folderFocused += baseView(folderDocUnderPrimary).apply { setClassName(parts[0], parts[1]) }
            }

            val encodedDoc = Uri.parse(
                "content://$authority/document/" + Uri.encode(docId),
            )
            folderFocused += baseView(encodedDoc)

            // Volume-root fallback only after folder-focused attempts (OEM residual).
            rootFallback += browse(rootDocUri)
            rootFallback += baseView(rootDocUri)
            for (component in components.take(3)) {
                val parts = component.split('/', limit = 2)
                rootFallback += baseView(rootDocUri).apply { setClassName(parts[0], parts[1]) }
            }
        }

        // Material Files + other managers: absolute file:// path (never FileProvider).
        val fileUri = Uri.parse("file://$abs")
        folderFocused += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, DocumentsContract.Document.MIME_TYPE_DIR)
            setPackage("me.zhanghai.android.files")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        folderFocused += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "resource/folder")
            setPackage("me.zhanghai.android.files")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        folderFocused += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "resource/folder")
            putExtra("org.openintents.extra.ABSOLUTE_PATH", abs)
            putExtra("com.sec.android.app.myfiles.PICK_DATA", abs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        folderFocused += Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, DocumentsContract.Document.MIME_TYPE_DIR)
            putExtra("org.openintents.extra.ABSOLUTE_PATH", abs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        folderFocused += Intent("com.sec.android.app.myfiles.VIEW").apply {
            putExtra("FOLDERPATH", abs)
            putExtra("com.sec.android.app.myfiles.PICK_DATA", abs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        for (intent in folderFocused) {
            if (tryStart(context, intent, requireResolved = true)) return true
        }
        for (intent in folderFocused) {
            if (tryStart(context, intent, requireResolved = false)) return true
        }
        // Last: stock Files at volume root (documented OEM limit).
        for (intent in rootFallback) {
            if (tryStart(context, intent, requireResolved = true)) return true
        }
        for (intent in rootFallback) {
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
