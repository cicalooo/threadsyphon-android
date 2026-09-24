package com.threadsyphon.android.ui.threads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threadsyphon.android.R
import com.threadsyphon.android.data.db.WatchedThreadEntity
import com.threadsyphon.android.ui.components.ThumbnailImage
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.WatchStatus
import com.threadsyphon.android.data.model.thumbUrl
import com.threadsyphon.android.service.WatchService
import com.threadsyphon.android.util.StorageHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadListScreen(repository: WatchRepository, onOpenDetail: (String) -> Unit) {
    val settings by repository.settings().collectAsState(initial = com.threadsyphon.android.data.model.AppSettings())
    val allThreads by repository.observeThreads().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var showFinished by remember { mutableStateOf(false) }
    val lowStorage = remember {
        StorageHelper.isLowStorage(StorageHelper.sharedRoot())
    }

    val threads = remember(allThreads, showFinished, settings.autoHideFinished) {
        when {
            showFinished -> allThreads.filter { it.hidden || it.status == WatchStatus.Complete.name }
            settings.autoHideFinished -> allThreads.filter {
                !it.hidden && it.status != WatchStatus.Complete.name
            }
            else -> allThreads.filter { !it.hidden }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_brand_mark),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "threadsyphon",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                when {
                                    selected.isNotEmpty() -> "${selected.size} selected"
                                    showFinished -> "Finished"
                                    else -> "Active"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                repository.setPaused(selected.toList(), false)
                                WatchService.start(context)
                                selected = emptySet()
                            }
                        }) { Icon(Icons.Default.PlayArrow, "Resume") }
                        IconButton(onClick = {
                            scope.launch {
                                repository.setPaused(selected.toList(), true)
                                selected = emptySet()
                            }
                        }) { Icon(Icons.Default.Pause, "Pause") }
                        IconButton(onClick = {
                            scope.launch {
                                repository.checkNow(selected.toList())
                                selected = emptySet()
                            }
                        }) { Icon(Icons.Default.Refresh, "Check") }
                        IconButton(onClick = {
                            scope.launch {
                                repository.remove(selected.toList())
                                selected = emptySet()
                            }
                        }) { Icon(Icons.Default.Delete, "Remove") }
                    } else {
                        IconButton(onClick = { showFinished = !showFinished }) {
                            Icon(
                                if (showFinished) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                if (showFinished) "Show active" else "Show finished",
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                repository.startAll()
                                WatchService.start(context)
                            }
                        }) { Icon(Icons.Default.PlayArrow, "Start all") }
                        IconButton(onClick = {
                            scope.launch { repository.pauseAll() }
                        }) { Icon(Icons.Default.Pause, "Pause all") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true; addError = null }) {
                Icon(Icons.Default.Add, "Add")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (lowStorage) {
                Card(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text("Low storage — downloads may fail.", Modifier.padding(12.dp))
                }
            }
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showFinished,
                    onClick = { showFinished = false },
                    label = { Text("Active") },
                )
                FilterChip(
                    selected = showFinished,
                    onClick = { showFinished = true },
                    label = { Text("Finished") },
                )
            }
            if (threads.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (showFinished) "No finished threads" else "No active threads",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (showFinished) "Completed watches appear here."
                        else "Paste a 4chan URL, Find, or Share To threadsyphon.",
                        Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(threads, key = { it.id }) { t ->
                        val isSelected = t.id in selected
                        Card(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (isSelected) selected - t.id else selected + t.id
                                    } else onOpenDetail(t.id)
                                },
                                onLongClick = {
                                    selected = if (isSelected) selected - t.id else selected + t.id
                                },
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        ) {
                            ThreadRow(
                                t = t,
                                onPause = {
                                    scope.launch { repository.setPaused(listOf(t.id), true) }
                                },
                                onResume = {
                                    scope.launch {
                                        repository.setPaused(listOf(t.id), false)
                                        WatchService.start(context)
                                    }
                                },
                                onUnhide = {
                                    scope.launch { repository.setHidden(listOf(t.id), false) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add & watch") },
            text = {
                Column {
                    OutlinedTextField(
                        urlInput,
                        { urlInput = it; addError = null },
                        label = { Text("4chan thread URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    addError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            repository.addThreadFromUrl(urlInput.trim(), true)
                            WatchService.start(context)
                            urlInput = ""
                            showAdd = false
                        } catch (e: Exception) {
                            addError = e.message
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ThreadRow(
    t: WatchedThreadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUnhide: () -> Unit,
) {
    val title = t.label.ifBlank { t.subject }.ifBlank { "/${t.board}/ · ${t.threadNo}" }
    val thumb = thumbUrl(t.board, t.thumbTim)
    val downloading = t.status == WatchStatus.Downloading.name
    val total = t.totalFiles
    val saved = t.savedCount
    val progress = if (total > 0) (saved.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val paused = t.status == WatchStatus.Paused.name

    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            ThumbnailImage(url = thumb, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("/${t.board}/${t.threadNo}", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(statusLabel(t.status), style = MaterialTheme.typography.labelMedium)
                Text(
                    if (total > 0) "$saved/$total files" else "$saved saved",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (downloading && total > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                )
            } else if (downloading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                )
            }
            if (t.lastError.isNotBlank()) {
                Text(
                    t.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (t.nextCheckAt > 0 && !downloading) {
                Text("Next ${formatTime(t.nextCheckAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                t.hidden || t.status == WatchStatus.Complete.name -> {
                    IconButton(onClick = onUnhide) {
                        Icon(Icons.Default.Visibility, "Restore")
                    }
                }
                paused -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, "Resume")
                    }
                }
                else -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, "Pause")
                    }
                }
            }
        }
    }
}

fun statusLabel(status: String): String = when (status) {
    WatchStatus.Ready.name -> "Ready"
    WatchStatus.Watching.name -> "Watching"
    WatchStatus.Downloading.name -> "Downloading"
    WatchStatus.Paused.name -> "Paused"
    WatchStatus.Complete.name -> "Complete"
    WatchStatus.Error.name -> "Error"
    WatchStatus.StoppedLowStorage.name -> "Stopped (low storage)"
    else -> status
}

fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
