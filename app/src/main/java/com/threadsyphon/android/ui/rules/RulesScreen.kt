package com.threadsyphon.android.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.threadsyphon.android.data.db.WatchRuleEntity
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.newId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(repository: WatchRepository) {
    val rules by repository.observeRules().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rules") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            Text(
                "Watchdogs scout the catalog while the watch service is running.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (rules.isEmpty()) {
                Text("No rules yet. Add one or create from Find → watchdog.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.name, style = MaterialTheme.typography.titleSmall)
                                    Text("/${rule.board}/ · ${rule.query}", style = MaterialTheme.typography.bodySmall)
                                    Text("every ${rule.intervalSec}s · limit ${rule.matchLimit}", style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = rule.enabled, onCheckedChange = { scope.launch { repository.setRuleEnabled(rule.id, it) } })
                                IconButton(onClick = { scope.launch { repository.deleteRule(rule.id) } }) {
                                    Icon(Icons.Default.Delete, "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var board by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(board, { board = it.filter { c -> c.isLetterOrDigit() }.take(10) }, label = { Text("Board") }, singleLine = true)
                    OutlinedTextField(query, { query = it }, label = { Text("Query") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = board.isNotBlank() && query.isNotBlank(),
                    onClick = {
                        scope.launch {
                            repository.upsertRule(
                                WatchRuleEntity(
                                    id = newId(),
                                    name = name.ifBlank { "/$board/ rule" },
                                    board = board.lowercase(),
                                    query = query,
                                ),
                            )
                            showAdd = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
