package com.threadsyphon.android.ui.find

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.threadsyphon.android.data.db.WatchRuleEntity
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.data.model.CatalogThread
import com.threadsyphon.android.data.model.newId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Survives Compose disposal / bottom-nav switches (Nav saveState + ViewModelStore).
 * Board + query also persist to DataStore for cold start.
 */
class FindViewModel(
    private val repository: WatchRepository,
) : ViewModel() {
    var board by mutableStateOf("g")
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<CatalogThread>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            board = repository.findBoard().first().ifBlank { "g" }
            query = repository.findQuery().first()
        }
    }

    fun onBoardChange(value: String) {
        board = value.filter { it.isLetterOrDigit() }.take(10)
        viewModelScope.launch { repository.setFindBoard(board) }
    }

    fun onQueryChange(value: String) {
        query = value
        viewModelScope.launch { repository.setFindQuery(query) }
    }

    fun search() {
        viewModelScope.launch {
            busy = true
            error = null
            info = null
            try {
                repository.setFindState(board, query)
                val hits = repository.searchCatalog(board, query)
                results = hits
                val b = board.trim().lowercase().trim('/')
                info = "${hits.size} hit(s) on /$b/"
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
                results = emptyList()
            } finally {
                busy = false
            }
        }
    }

    fun addWatch(hit: CatalogThread, onStarted: () -> Unit) {
        viewModelScope.launch {
            repository.addFromCatalog(hit)
            onStarted()
            info = "Added ${hit.shortId}"
        }
    }

    fun addRule(hit: CatalogThread) {
        viewModelScope.launch {
            val q = query.ifBlank {
                hit.title.takeIf { it.isNotBlank() }?.let { "title:\"$it\"" } ?: hit.shortId
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
    }

    companion object {
        fun factory(repository: WatchRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(FindViewModel::class.java)) {
                        return FindViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
                }
            }
    }
}
