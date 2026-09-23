package com.threadsyphon.android.ui.folder

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.DownloadLocation
import com.threadsyphon.android.util.FolderBrowseInfo
import com.threadsyphon.android.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadFolderScreen(
    threadId: String,
    repository: WatchRepository,
    onBack: () -> Unit,
) {
    val thread by repository.observeThread(threadId).collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = thread

    var browse by remember { mutableStateOf<FolderBrowseInfo?>(null) }
    var hasAllFiles by remember { mutableStateOf(StorageHelper.hasAllFilesAccess()) }

    fun refresh() {
        val current = t ?: return
        scope.launch {
            var settings = repository.currentSettings()
            if (
                settings.downloadLocation == DownloadLocation.AppExternal ||
                settings.downloadLocation == DownloadLocation.MediaStoreDownloads
            ) {
                repository.updateSettings {
                    it.copy(downloadLocation = DownloadLocation.SharedRoot)
                }
                settings = repository.currentSettings()
            }
            val info = withContext(Dispatchers.IO) {
                StorageHelper.prepareThreadFolderBrowse(
                    context,
                    current.board,
                    current.threadNo,
                    settings,
                )
            }
            browse = info
            hasAllFiles = StorageHelper.hasAllFilesAccess()
            if (info.migratedPrivateFiles > 0) {
                Toast.makeText(
                    context,
                    "Moved ${info.migratedPrivateFiles} file(s) from private staging",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, t?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (t != null) refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val title = t?.let {
        it.label.ifBlank { it.subject }.ifBlank { "/${it.board}/${it.threadNo}" }
    } ?: "Folder"
    val path = browse?.folder?.absolutePath
        ?: t?.let {
            StorageHelper.sharedThreadFolder(it.board, it.threadNo).absolutePath
        }.orEmpty()
    val files = browse?.files.orEmpty()
    val listFailed = browse?.listReadable == false
    val showGrant = !hasAllFiles || browse?.needsAllFilesAccess == true || listFailed

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path.isNotBlank()) {
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (path.isBlank()) return@IconButton
                            StorageHelper.copyPathToClipboard(context, path)
                            Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, "Copy path")
                    }
                },
            )
        },
    ) { padding ->
        if (t == null) {
            Text("Thread not found", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Folder: ${t.folderRelative.ifBlank { "${t.board}/${t.threadNo}" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (showGrant) {
                    Button(
                        onClick = {
                            StorageHelper.launchAllFilesAccess(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Security, null)
                        Text(" Grant all files access")
                    }
                    Text(
                        "Shared Internal storage/threadsyphon needs All files access on Android 11+ to list and open this folder reliably.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        val folder = browse?.folder ?: StorageHelper.sharedThreadFolder(t.board, t.threadNo)
                        val opened = StorageHelper.openInFilesChooser(context, folder)
                        if (!opened) {
                            StorageHelper.copyPathToClipboard(context, folder.absolutePath)
                            Toast.makeText(
                                context,
                                "No file manager handled the folder — path copied",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, null)
                    Text(" Open in Files…")
                }
                OutlinedButton(
                    onClick = {
                        StorageHelper.copyPathToClipboard(context, path)
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Text(" Copy path")
                }
            }

            when {
                browse == null -> {
                    Text(
                        "Loading…",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                listFailed -> {
                    EmptyFolderState(
                        title = "Can't list this folder yet",
                        body = if (!hasAllFiles) {
                            "Grant All files access above, then return here. Path: $path"
                        } else {
                            "Folder may be missing or unreadable. Path: $path"
                        },
                    )
                }
                files.isEmpty() -> {
                    EmptyFolderState(
                        title = "Folder is empty",
                        body = if (browse?.hadPrivateStagingOnly == true) {
                            "No files in the shared folder yet. Older downloads may still be under app-private staging until access/migration succeeds.\n$path"
                        } else {
                            path
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(files, key = { it.absolutePath }) { file ->
                            FolderFileRow(
                                file = file,
                                onClick = {
                                    val ok = StorageHelper.openFileWithProvider(context, file)
                                    if (!ok) {
                                        Toast.makeText(
                                            context,
                                            "Couldn't open ${file.name}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderState(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderFileRow(file: File, onClick: () -> Unit) {
    val ext = file.extension.lowercase(Locale.US).let { if (it.isEmpty()) "" else ".$it" }
    val mime = StorageHelper.mimeForExt(ext)
    val icon = mimeIcon(mime)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatSize(file.length()) + " · " + mime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Default.Image
    mime.startsWith("video/") -> Icons.Default.Movie
    mime.startsWith("audio/") -> Icons.Default.AudioFile
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
