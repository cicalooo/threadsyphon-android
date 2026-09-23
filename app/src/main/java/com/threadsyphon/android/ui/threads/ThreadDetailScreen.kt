package com.threadsyphon.android.ui.threads

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.DownloadLocation
import com.threadsyphon.android.data.model.WatchStatus
import com.threadsyphon.android.service.WatchService
import com.threadsyphon.android.util.StorageHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(threadId: String, repository: WatchRepository, onBack: () -> Unit) {
    val thread by repository.observeThread(threadId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = thread

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        t?.let { it.label.ifBlank { it.subject }.ifBlank { "/${it.board}/${it.threadNo}" } }
                            ?: "Thread",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (t != null) {
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)))
                        }) {
                            Icon(Icons.Default.OpenInBrowser, "Browser")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (t == null) {
            Text("Thread not found", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val total = t.totalFiles
        val saved = t.savedCount
        val downloading = t.status == WatchStatus.Downloading.name
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("/${t.board}/${t.threadNo}", style = MaterialTheme.typography.titleMedium)
            Text(t.url, style = MaterialTheme.typography.bodySmall)
            Text("Status: ${statusLabel(t.status)}")
            if (total > 0) {
                Text("Progress: $saved / $total files")
                LinearProgressIndicator(
                    progress = { (saved.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("Saved: $saved")
                if (downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text("Interval: ${t.intervalSec}s · Filter: ${t.mediaFilter} · Names: ${t.filenameMode}")
            if (t.lastCheckedAt > 0) Text("Last check: ${formatTime(t.lastCheckedAt)}")
            if (t.nextCheckAt > 0) Text("Next check: ${formatTime(t.nextCheckAt)}")
            if (t.lastError.isNotBlank()) Text(t.lastError, color = MaterialTheme.colorScheme.error)
            Text(
                "Folder: ${t.folderRelative.ifBlank { "${t.board}/${t.threadNo}" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    scope.launch {
                        var settings = repository.currentSettings()
                        // Legacy AppExternal / MediaStoreDownloads → shared root.
                        if (
                            settings.downloadLocation == DownloadLocation.AppExternal ||
                            settings.downloadLocation == DownloadLocation.MediaStoreDownloads
                        ) {
                            repository.updateSettings {
                                it.copy(downloadLocation = DownloadLocation.SharedRoot)
                            }
                            settings = repository.currentSettings()
                        }
                        val outcome = StorageHelper.openThreadFolder(
                            context,
                            t.board,
                            t.threadNo,
                            settings,
                        )
                        val path = outcome.folder.absolutePath
                        if (outcome.needsAllFilesAccess) {
                            // Do not pretend open worked; copy filesystem path (never FileProvider URI).
                            StorageHelper.copyPathToClipboard(context, path)
                            val stagingNote = if (outcome.hadPrivateStagingOnly) {
                                " Older files may still be under app-private staging until you grant access."
                            } else {
                                ""
                            }
                            Toast.makeText(
                                context,
                                "Grant All files access to open Internal storage/threadsyphon — path copied: $path.$stagingNote",
                                Toast.LENGTH_LONG,
                            ).show()
                            try {
                                context.startActivity(StorageHelper.allFilesAccessIntent(context))
                            } catch (_: Exception) {
                            }
                            return@launch
                        }
                        if (outcome.opened) {
                            if (outcome.migratedPrivateFiles > 0) {
                                Toast.makeText(
                                    context,
                                    "Moved ${outcome.migratedPrivateFiles} file(s) from private staging into $path",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else if (outcome.hadPrivateStagingOnly) {
                                Toast.makeText(
                                    context,
                                    "Opened shared folder. Some older files remain under app-private staging.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            return@launch
                        }
                        StorageHelper.copyPathToClipboard(context, path)
                        Toast.makeText(
                            context,
                            "Couldn't open Files — path copied: $path",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Text(" Open folder")
            }
            if (!StorageHelper.hasAllFilesAccess()) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(StorageHelper.allFilesAccessIntent(context))
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Open settings failed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant All files access (shared folder)")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        repository.setPaused(listOf(t.id), false)
                        WatchService.start(context)
                        repository.checkNow(listOf(t.id))
                    }
                }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(" Resume")
                }
                OutlinedButton(onClick = {
                    scope.launch { repository.setPaused(listOf(t.id), true) }
                }) {
                    Icon(Icons.Default.Pause, null)
                    Text(" Pause")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { repository.checkNow(listOf(t.id)) }
                }) {
                    Icon(Icons.Default.Refresh, null)
                    Text(" Check")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        repository.remove(listOf(t.id))
                        onBack()
                    }
                }) {
                    Icon(Icons.Default.Delete, null)
                    Text(" Remove")
                }
            }
        }
    }
}
