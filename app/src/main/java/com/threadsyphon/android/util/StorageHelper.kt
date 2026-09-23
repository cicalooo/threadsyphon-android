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
import androidx.core.content.FileProvider
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

data class FolderBrowseInfo(
    val folder: File,
    val needsAllFilesAccess: Boolean,
    val migratedPrivateFiles: Int = 0,
    val hadPrivateStagingOnly: Boolean = false,
    /** False when listFiles() is null (no permission / not a directory). */
    val listReadable: Boolean,
    val files: List<File>,
)

/**
 * Result of [StorageHelper.openInFilesChooser].
 * [opened] true when an external file manager Activity was started.
 * On failure, [pathCopied] is set and [suggestInstallMaterialFiles] may be true.
 */
data class OpenInFilesResult(
    val opened: Boolean,
    val pathCopied: Boolean = false,
    val suggestInstallMaterialFiles: Boolean = false,
    val message: String = "",
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

    /** Launch all-files settings; tries package-specific then the global manager screen. */
    fun launchAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return tryStart(context, allFilesAccessIntent(context), requireResolved = false)
        }
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, packageIntent, requireResolved = false)) return true
        val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, global, requireResolved = false)) return true
        return tryStart(context, allFilesAccessIntent(context), requireResolved = false)
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
     * Resolve the shared (or custom) thread folder for the in-app browser.
     * Migrates private staging when all-files access allows writing the shared tree.
     * Does not launch external file managers — that is optional via [openInFilesChooser].
     */
    fun prepareThreadFolderBrowse(
        context: Context,
        board: String,
        threadNo: Long,
        settings: AppSettings,
    ): FolderBrowseInfo {
        val sharedFolder = sharedThreadFolder(board, threadNo)
        val custom = if (settings.downloadLocation == DownloadLocation.CustomPath) {
            customRoot(settings.customRootPath)?.let { File(it, "$board/$threadNo") }
        } else {
            null
        }
        val targetFolder = custom ?: sharedFolder
        val needsAllFiles = (custom == null) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !hasAllFilesAccess()

        var migrated = 0
        if (!needsAllFiles) {
            targetFolder.mkdirs()
            ensureWritableDir(targetFolder)
            if (custom == null) {
                migrated = migratePrivateThreadMediaToShared(context, board, threadNo, sharedFolder)
            }
        } else {
            // Best-effort mkdir; may fail without all-files — UI still shows the expected path.
            try {
                targetFolder.mkdirs()
            } catch (_: Exception) {
            }
        }

        val listed = try {
            targetFolder.listFiles()
        } catch (_: Exception) {
            null
        }
        val listReadable = listed != null && targetFolder.isDirectory
        val files = (listed ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.name.lowercase(java.util.Locale.US) }
        val stillPrivateOnly = custom == null &&
            migrated == 0 &&
            privateThreadHasMedia(context, board, threadNo) &&
            files.isEmpty()

        return FolderBrowseInfo(
            folder = targetFolder,
            needsAllFilesAccess = needsAllFiles,
            migratedPrivateFiles = migrated,
            hadPrivateStagingOnly = stillPrivateOnly,
            listReadable = listReadable,
            files = files,
        )
    }

    /**
     * Open a single media file via FileProvider + ACTION_VIEW.
     * Directories are refused — never hand folder URIs through FileProvider.
     */
    fun openFileWithProvider(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val ext = file.extension.lowercase(java.util.Locale.US).let { if (it.isEmpty()) "" else ".$it" }
            val mime = mimeForExt(ext)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            val chooser = Intent.createChooser(view, "Open ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Open [folder] in an external file manager.
     *
     * Does **not** rely on createChooser alone (many OEMs resolve zero Activities for
     * directory MIME + Documents URIs, which yields "no app" / silent failure).
     * Prefers explicit package launches when installed, then MATCH_ALL query results,
     * and only then a chooser built from known handlers. Never uses FileProvider for dirs.
     */
    fun openInFilesChooser(context: Context, folder: File): OpenInFilesResult {
        try {
            folder.mkdirs()
        } catch (_: Exception) {
        }
        val abs = try {
            folder.canonicalFile.absolutePath
        } catch (_: Exception) {
            folder.absolutePath
        }
        if (abs.contains("/Android/data/") || abs.contains("/Android/obb/")) {
            return failOpenInFiles(
                context,
                abs,
                "App-private paths cannot be opened in external file managers. Use the in-app list.",
            )
        }

        val relative = primaryRelativePath(abs)
            ?: return failOpenInFiles(
                context,
                abs,
                "Folder is not under shared primary storage. Use the in-app list or paste the path.",
            )

        val authority = "com.android.externalstorage.documents"
        val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        val treePrimary = DocumentsContract.buildTreeDocumentUri(authority, "primary:")
        val treeTs = DocumentsContract.buildTreeDocumentUri(
            authority,
            "primary:${Constants.SHARED_ROOT_FOLDER}",
        )
        val folderDocUnderPrimary = DocumentsContract.buildDocumentUriUsingTree(treePrimary, docId)
        val folderDocUnderTs = DocumentsContract.buildDocumentUriUsingTree(treeTs, docId)
        val plainDocUri = DocumentsContract.buildDocumentUri(authority, docId)
        val encodedDoc = Uri.parse("content://$authority/document/" + Uri.encode(docId))
        val fileUri = Uri.parse("file://$abs")
        val dirMimes = listOf(
            DocumentsContract.Document.MIME_TYPE_DIR,
            "resource/folder",
            "inode/directory",
        )
        val docUris = listOf(plainDocUri, encodedDoc, folderDocUnderTs, folderDocUnderPrimary)

        // --- 1) Explicit known managers (detect package first) ---
        if (isPackageInstalled(context, PKG_MATERIAL_FILES)) {
            val materialIntents = mutableListOf<Intent>()
            // Document URIs + directory MIMEs (FileListActivity registers these)
            for (uri in docUris) {
                for (mime in dirMimes) {
                    materialIntents += dirViewIntent(uri, mime).apply {
                        setClassName(PKG_MATERIAL_FILES, ACTIVITY_MATERIAL_FILES)
                    }
                    materialIntents += dirViewIntent(uri, mime).apply {
                        setPackage(PKG_MATERIAL_FILES)
                    }
                }
            }
            // file:// absolute path with setPackage (Material Files often accepts this)
            for (mime in dirMimes) {
                materialIntents += Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mime)
                    setClassName(PKG_MATERIAL_FILES, ACTIVITY_MATERIAL_FILES)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                materialIntents += Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mime)
                    setPackage(PKG_MATERIAL_FILES)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            for (intent in materialIntents) {
                if (tryStart(context, intent, requireResolved = true)) {
                    return OpenInFilesResult(opened = true)
                }
            }
            // Last resort for Material Files: setPackage VIEW without requiring resolve
            for (mime in dirMimes) {
                val loose = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(plainDocUri, mime)
                    setPackage(PKG_MATERIAL_FILES)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
                if (tryStart(context, loose, requireResolved = false)) {
                    return OpenInFilesResult(opened = true)
                }
            }
        }

        // AOSP / Google DocumentsUI FilesActivity
        for (pkg in listOf(PKG_DOCUMENTSUI_GOOGLE, PKG_DOCUMENTSUI_AOSP)) {
            if (!isPackageInstalled(context, pkg)) continue
            for (uri in docUris) {
                val intents = listOf(
                    dirViewIntent(uri, DocumentsContract.Document.MIME_TYPE_DIR).apply {
                        setClassName(pkg, ACTIVITY_DOCUMENTSUI_FILES)
                    },
                    browseIntent(uri).apply {
                        setClassName(pkg, ACTIVITY_DOCUMENTSUI_FILES)
                    },
                    dirViewIntent(uri, DocumentsContract.Document.MIME_TYPE_DIR).apply {
                        setPackage(pkg)
                    },
                )
                for (intent in intents) {
                    if (tryStart(context, intent, requireResolved = true)) {
                        return OpenInFilesResult(opened = true)
                    }
                }
            }
        }

        // Samsung My Files
        if (isPackageInstalled(context, PKG_SAMSUNG_MYFILES)) {
            val samsung = listOf(
                Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES").apply {
                    setPackage(PKG_SAMSUNG_MYFILES)
                    putExtra("samsung.myfiles.intent.extra.START_PATH", abs)
                    putExtra("FOLDERPATH", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent("com.sec.android.app.myfiles.VIEW").apply {
                    setPackage(PKG_SAMSUNG_MYFILES)
                    putExtra("FOLDERPATH", abs)
                    putExtra("com.sec.android.app.myfiles.PICK_DATA", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(PKG_SAMSUNG_MYFILES, "com.sec.android.app.myfiles.external.ui.MainActivity")
                    putExtra("FOLDERPATH", abs)
                    putExtra("samsung.myfiles.intent.extra.START_PATH", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            for (intent in samsung) {
                if (tryStart(context, intent, requireResolved = true)) {
                    return OpenInFilesResult(opened = true)
                }
                if (tryStart(context, intent, requireResolved = false)) {
                    return OpenInFilesResult(opened = true)
                }
            }
        }

        // Mi / Xiaomi File Manager
        for (pkg in listOf(PKG_MI_GLOBAL, PKG_MI_CN)) {
            if (!isPackageInstalled(context, pkg)) continue
            val mi = listOf(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "resource/folder")
                    setPackage(pkg)
                    putExtra("current_directory", abs)
                    putExtra("folder_path", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(pkg, "com.android.fileexplorer.FileExplorerTabActivity")
                    setDataAndType(fileUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    putExtra("current_directory", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            for (intent in mi) {
                if (tryStart(context, intent, requireResolved = true)) {
                    return OpenInFilesResult(opened = true)
                }
            }
        }

        // Solid Explorer, Amaze, FX — path extras / VIEW when present
        if (isPackageInstalled(context, PKG_SOLID_EXPLORER)) {
            val solid = listOf(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage(PKG_SOLID_EXPLORER)
                    setDataAndType(fileUri, "resource/folder")
                    putExtra("org.openintents.extra.ABSOLUTE_PATH", abs)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage(PKG_SOLID_EXPLORER)
                    setDataAndType(plainDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                },
            )
            for (intent in solid) {
                if (tryStart(context, intent, requireResolved = true)) {
                    return OpenInFilesResult(opened = true)
                }
            }
        }
        if (isPackageInstalled(context, PKG_AMAZE)) {
            val amaze = Intent(Intent.ACTION_VIEW).apply {
                setPackage(PKG_AMAZE)
                setDataAndType(fileUri, "resource/folder")
                putExtra("org.openintents.extra.ABSOLUTE_PATH", abs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, amaze, requireResolved = true)) {
                return OpenInFilesResult(opened = true)
            }
        }
        if (isPackageInstalled(context, PKG_FX)) {
            val fx = Intent(Intent.ACTION_VIEW).apply {
                setPackage(PKG_FX)
                setDataAndType(fileUri, "resource/folder")
                putExtra("org.openintents.extra.ABSOLUTE_PATH", abs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, fx, requireResolved = true)) {
                return OpenInFilesResult(opened = true)
            }
        }

        // Google Files app (not always directory-capable, but try)
        if (isPackageInstalled(context, PKG_GOOGLE_FILES)) {
            val g = dirViewIntent(plainDocUri, DocumentsContract.Document.MIME_TYPE_DIR).apply {
                setPackage(PKG_GOOGLE_FILES)
            }
            if (tryStart(context, g, requireResolved = true)) {
                return OpenInFilesResult(opened = true)
            }
        }

        // --- 2) queryIntentActivities MATCH_ALL for directory VIEW ---
        val handlers = queryDirectoryViewHandlers(context, docUris, dirMimes, fileUri)
        if (handlers.isNotEmpty()) {
            val best = handlers.first()
            val explicit = Intent(best.first).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setClassName(best.second.activityInfo.packageName, best.second.activityInfo.name)
            }
            if (tryStart(context, explicit, requireResolved = false)) {
                return OpenInFilesResult(opened = true)
            }
            // Chooser only when we know at least one handler exists
            val primary = Intent(handlers.first().first).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setClassName(
                    handlers.first().second.activityInfo.packageName,
                    handlers.first().second.activityInfo.name,
                )
            }
            val alts = handlers.drop(1).take(4).map { (base, ri) ->
                Intent(base).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
            }.toTypedArray()
            val chooser = Intent.createChooser(primary, "Open folder with").apply {
                if (alts.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, alts)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, chooser, requireResolved = false)) {
                return OpenInFilesResult(opened = true)
            }
        }

        // --- 3) Nothing can open — clear message + copy path (optional Play Store) ---
        return failOpenInFiles(
            context,
            abs,
            "No file manager on this phone accepts folder opens from other apps. " +
                "Use the in-app list, or paste the path in Material Files.",
        )
    }

    fun materialFilesPlayStoreIntent(): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$PKG_MATERIAL_FILES"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun materialFilesPlayStoreWebIntent(): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$PKG_MATERIAL_FILES"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launchMaterialFilesStore(context: Context): Boolean {
        if (tryStart(context, materialFilesPlayStoreIntent(), requireResolved = true)) return true
        return tryStart(context, materialFilesPlayStoreWebIntent(), requireResolved = false)
    }

    private fun failOpenInFiles(context: Context, abs: String, message: String): OpenInFilesResult {
        copyPathToClipboard(context, abs)
        return OpenInFilesResult(
            opened = false,
            pathCopied = true,
            suggestInstallMaterialFiles = !isPackageInstalled(context, PKG_MATERIAL_FILES),
            message = message,
        )
    }

    private fun primaryRelativePath(abs: String): String? {
        val primaryRoot = try {
            Environment.getExternalStorageDirectory().canonicalFile.absolutePath
        } catch (_: Exception) {
            Environment.getExternalStorageDirectory().absolutePath
        }
        return when {
            abs == primaryRoot -> ""
            abs.startsWith("$primaryRoot/") -> abs.removePrefix("$primaryRoot/").trim('/')
            abs.startsWith("/sdcard/") -> abs.removePrefix("/sdcard/").trim('/')
            abs.startsWith("/storage/emulated/0/") -> abs.removePrefix("/storage/emulated/0/").trim('/')
            else -> null
        }
    }

    private fun dirViewIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }

    private fun browseIntent(uri: Uri): Intent =
        Intent("android.provider.action.BROWSE").apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun queryDirectoryViewHandlers(
        context: Context,
        docUris: List<Uri>,
        dirMimes: List<String>,
        fileUri: Uri,
    ): List<Pair<Intent, android.content.pm.ResolveInfo>> {
        val pm = context.packageManager
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Pair<Intent, android.content.pm.ResolveInfo>>()
        val probeUris = docUris + fileUri
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            PackageManager.ResolveInfoFlags.of(
                (PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY).toLong(),
            )
        } else {
            null
        }
        for (uri in probeUris) {
            for (mime in dirMimes) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
                val list = try {
                    if (flags != null) {
                        pm.queryIntentActivities(intent, flags)
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(
                            intent,
                            PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                for (ri in list) {
                    val ai = ri.activityInfo ?: continue
                    // Skip our own package
                    if (ai.packageName == context.packageName) continue
                    val key = "${ai.packageName}/${ai.name}"
                    if (seen.add(key)) out += intent to ri
                }
            }
        }
        return out
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
     * Open [folder] in an external file manager. Delegates to [openInFilesChooser].
     */
    fun openFolderInFileManager(context: Context, folder: File): Boolean =
        openInFilesChooser(context, folder).opened

    private const val PKG_MATERIAL_FILES = "me.zhanghai.android.files"
    private const val ACTIVITY_MATERIAL_FILES = "me.zhanghai.android.files.filelist.FileListActivity"
    private const val PKG_DOCUMENTSUI_GOOGLE = "com.google.android.documentsui"
    private const val PKG_DOCUMENTSUI_AOSP = "com.android.documentsui"
    private const val ACTIVITY_DOCUMENTSUI_FILES = "com.android.documentsui.files.FilesActivity"
    private const val PKG_SAMSUNG_MYFILES = "com.sec.android.app.myfiles"
    private const val PKG_MI_GLOBAL = "com.mi.android.globalFileexplorer"
    private const val PKG_MI_CN = "com.android.fileexplorer"
    private const val PKG_SOLID_EXPLORER = "pl.solidexplorer2"
    private const val PKG_AMAZE = "com.amaze.filemanager"
    private const val PKG_FX = "nextapp.fx"
    private const val PKG_GOOGLE_FILES = "com.google.android.apps.nbu.files"

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
