package com.threadsyphon.android.ui.find

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threadsyphon.android.data.db.WatchRuleEntity
import com.threadsyphon.android.ui.components.ThumbnailImage
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.CatalogThread
import com.threadsyphon.android.data.model.newId
import com.threadsyphon.android.service.WatchService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindScreen(repository: WatchRepository) {
    var board by remember { mutableStateOf("g") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogThread>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun runSearch() {
        scope.launch {
            busy = true
            error = null
            info = null
            try {
                val hits = repository.searchCatalog(board, query)
                results = hits
                info = "${hits.size} hit(s) on /${board.trim().lowercase().trim('/')}/"
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
                results = emptyList()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Find") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    board,
                    { board = it.filter { c -> c.isLetterOrDigit() }.take(10) },
                    label = { Text("Board") },
                    singleLine = true,
                    modifier = Modifier.weight(0.35f),
                    enabled = !busy,
                )
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Keywords / query") },
                    singleLine = true,
                    modifier = Modifier.weight(0.65f),
                    enabled = !busy,
                )
            }
            Text(
                "Examples: /caig/ · title:work · body:driver OR tag:wg · leave blank for full catalog",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !busy && board.isNotBlank(),
                onClick = { runSearch() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Searching…")
                } else {
                    Icon(Icons.Default.Search, null)
                    Text(" Search")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { "${it.board}/${it.no}" }) { hit ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                ThumbnailImage(url = hit.thumbnailUrl, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    hit.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${hit.shortId} · R${hit.replies} · I${hit.images}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    repository.addFromCatalog(hit)
                                    WatchService.start(context)
                                    info = "Added ${hit.shortId}"
                                }
                            }) { Icon(Icons.Default.Add, "Watch") }
                            IconButton(onClick = {
                                scope.launch {
                                    val q = query.ifBlank {
                                        hit.title.takeIf { it.isNotBlank() }?.let { "title:\"$it\"" }
                                            ?: hit.shortId
                                    }
                                    repository.upsertRule(
                                        WatchRuleEntity(
                                            id = newId(),
                                            name = "Find · /${hit.board}/",
                                            board = hit.board,
                                            query = q,
                                        ),
                                    )
                                    info = "Rule added"
                                }
                            }) { Icon(Icons.AutoMirrored.Filled.Rule, "Watchdog") }
                        }
                    }
                }
            }
        }
    }
}
