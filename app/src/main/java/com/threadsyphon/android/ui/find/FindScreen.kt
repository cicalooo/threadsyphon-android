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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.threadsyphon.android.ui.components.ThumbnailImage
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.service.WatchService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindScreen(
    repository: WatchRepository,
    viewModel: FindViewModel = viewModel(factory = FindViewModel.factory(repository)),
) {
    val board = viewModel.board
    val query = viewModel.query
    val results = viewModel.results
    val busy = viewModel.busy
    val error = viewModel.error
    val info = viewModel.info
    val context = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text("Find") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    board,
                    { viewModel.onBoardChange(it) },
                    label = { Text("Board") },
                    singleLine = true,
                    modifier = Modifier.weight(0.35f),
                    enabled = !busy,
                )
                OutlinedTextField(
                    query,
                    { viewModel.onQueryChange(it) },
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
                onClick = { viewModel.search() },
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
                                viewModel.addWatch(hit) { WatchService.start(context) }
                            }) { Icon(Icons.Default.Add, "Watch") }
                            IconButton(onClick = {
                                viewModel.addRule(hit)
                            }) { Icon(Icons.AutoMirrored.Filled.Rule, "Watchdog") }
                        }
                    }
                }
            }
        }
    }
}
