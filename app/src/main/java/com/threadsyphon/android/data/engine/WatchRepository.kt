package com.threadsyphon.android.data.engine

import android.content.Context
import com.threadsyphon.android.data.db.AppDatabase
import com.threadsyphon.android.data.db.WatchRuleEntity
import com.threadsyphon.android.data.db.WatchedThreadEntity
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.data.model.CatalogThread
import com.threadsyphon.android.data.model.WatchStatus
import com.threadsyphon.android.data.model.newId
import com.threadsyphon.android.data.model.parseThreadUrl
import com.threadsyphon.android.data.network.FourChanClient
import com.threadsyphon.android.data.prefs.SettingsRepository
import com.threadsyphon.android.service.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class WatchRepository(
    context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
    private val settingsRepo: SettingsRepository = SettingsRepository(context),
    private val client: FourChanClient = FourChanClient(),
    private val engine: ThreadEngine = ThreadEngine(context, client),
) {
    private val appContext = context.applicationContext

    fun observeThreads(): Flow<List<WatchedThreadEntity>> = db.threads().observeAll()
    fun observeActiveThreads(): Flow<List<WatchedThreadEntity>> = db.threads().observeActive()
    fun observeHiddenThreads(): Flow<List<WatchedThreadEntity>> = db.threads().observeHidden()
    fun observeThread(id: String): Flow<WatchedThreadEntity?> = db.threads().observeById(id)
    fun observeRules(): Flow<List<WatchRuleEntity>> = db.rules().observeAll()
    fun observeActiveCount(): Flow<Int> = db.threads().observeActiveCount()
    fun settings(): Flow<AppSettings> = settingsRepo.settings

    suspend fun currentSettings(): AppSettings = settingsRepo.settings.first()

    suspend fun addThreadFromUrl(url: String, autoStart: Boolean = true): WatchedThreadEntity {
        val parsed = parseThreadUrl(url)
        db.threads().findByBoardThread(parsed.board, parsed.threadNo)?.let { return it }
        val settings = currentSettings()
        val entity = WatchedThreadEntity(
            id = newId(),
            url = parsed.canonicalUrl,
            board = parsed.board,
            threadNo = parsed.threadNo,
            intervalSec = settings.defaultInterval,
            mediaFilter = settings.mediaFilter,
            filenameMode = settings.filenameMode,
            maxFileMb = settings.maxFileMb,
            verifyMd5 = settings.verifyMd5,
            status = if (autoStart) WatchStatus.Ready.name else WatchStatus.Paused.name,
            autoStart = autoStart,
            folderRelative = "${parsed.board}/${parsed.threadNo}",
        )
        db.threads().upsert(entity)
        return entity
    }

    suspend fun addFromCatalog(hit: CatalogThread, labelPrefix: String = ""): WatchedThreadEntity {
        db.threads().findByBoardThread(hit.board, hit.no)?.let { return it }
        val settings = currentSettings()
        val entity = WatchedThreadEntity(
            id = newId(),
            url = hit.url,
            board = hit.board,
            threadNo = hit.no,
            label = labelPrefix.trim(),
            subject = hit.title,
            intervalSec = settings.defaultInterval,
            mediaFilter = settings.mediaFilter,
            filenameMode = settings.filenameMode,
            maxFileMb = settings.maxFileMb,
            verifyMd5 = settings.verifyMd5,
            status = WatchStatus.Ready.name,
            autoStart = true,
            folderRelative = "${hit.board}/${hit.no}",
            thumbTim = hit.tim,
        )
        db.threads().upsert(entity)
        return entity
    }

    suspend fun setPaused(ids: List<String>, paused: Boolean) {
        val status = if (paused) WatchStatus.Paused.name else WatchStatus.Ready.name
        db.threads().setStatus(ids, status)
    }

    suspend fun setHidden(ids: List<String>, hidden: Boolean) {
        db.threads().setHidden(ids, hidden)
        if (!hidden) {
            // Restoring to Active → ready to watch again if was complete
            for (id in ids) {
                val t = db.threads().getById(id) ?: continue
                if (t.status == WatchStatus.Complete.name) {
                    db.threads().upsert(t.copy(status = WatchStatus.Paused.name, hidden = false))
                }
            }
        }
    }

    suspend fun remove(ids: List<String>) {
        db.downloads().deleteForThreads(ids)
        db.threads().deleteByIds(ids)
    }

    suspend fun pauseAll() {
        val all = db.threads().getAll()
        val ids = all.filter {
            it.status == WatchStatus.Watching.name ||
                it.status == WatchStatus.Downloading.name ||
                it.status == WatchStatus.Ready.name
        }.map { it.id }
        if (ids.isNotEmpty()) db.threads().setStatus(ids, WatchStatus.Paused.name)
    }

    suspend fun startAll() {
        val all = db.threads().getAll()
        val ids = all.filter {
            !it.hidden && (
                it.status == WatchStatus.Paused.name ||
                    it.status == WatchStatus.Ready.name ||
                    it.status == WatchStatus.Error.name
                )
        }.map { it.id }
        if (ids.isNotEmpty()) db.threads().setStatus(ids, WatchStatus.Ready.name)
    }

    suspend fun checkNow(ids: List<String>) {
        for (id in ids) {
            val t = db.threads().getById(id) ?: continue
            if (t.status == WatchStatus.Paused.name) continue
            runCheck(t)
        }
    }

    suspend fun runDueChecks() {
        val settings = currentSettings()
        val now = System.currentTimeMillis()
        val runnable = db.threads().getAll().filter { t ->
            if (t.hidden) return@filter false
            when (t.status) {
                WatchStatus.Ready.name, WatchStatus.Watching.name ->
                    t.nextCheckAt == 0L || t.nextCheckAt <= now
                WatchStatus.Error.name -> t.nextCheckAt <= now
                else -> false
            }
        }
        for (t in runnable) {
            runCheck(t, settings)
        }
    }

    suspend fun runCheck(thread: WatchedThreadEntity, settings: AppSettings? = null) {
        val s = settings ?: currentSettings()
        val known = db.downloads().keysForThread(thread.id).toSet()
        db.threads().upsert(thread.copy(status = WatchStatus.Downloading.name))
        val result = engine.checkAndDownload(
            thread = thread,
            settings = s,
            knownKeys = known,
            onFileSaved = { db.downloads().upsert(it) },
            onProgress = { _, _, working -> db.threads().upsert(working) },
        )
        db.threads().upsert(result.entity)
        if (s.notifications) {
            val title = result.entity.label.ifBlank { result.entity.subject }
                .ifBlank { "/${result.entity.board}/${result.entity.threadNo}" }
            when {
                result.gone404 -> NotificationHelper.notifyEvent(
                    appContext,
                    "Thread 404",
                    "$title is gone",
                )
                result.archivedOrGone -> NotificationHelper.notifyEvent(
                    appContext,
                    "Thread complete",
                    "$title finished (archived/closed)",
                )
            }
        }
    }

    suspend fun searchCatalog(board: String, query: String): List<CatalogThread> =
        withContext(Dispatchers.IO) {
            val b = board.trim().lowercase().trim('/')
            require(b.isNotBlank()) { "Enter a board (e.g. g, wsg, a)." }
            val all = client.fetchCatalog(b)
            filterCatalog(all, query)
        }

    suspend fun upsertRule(rule: WatchRuleEntity) = db.rules().upsert(rule)
    suspend fun deleteRule(id: String) = db.rules().delete(id)
    suspend fun setRuleEnabled(id: String, enabled: Boolean) = db.rules().setEnabled(id, enabled)

    suspend fun scoutOnce(): Int {
        val rules = db.rules().getEnabled()
        val watched = db.threads().getAll().map { "${it.board}/${it.threadNo}" }.toSet()
        var added = 0
        for (rule in rules) {
            val hits = try {
                searchCatalog(rule.board, rule.query)
            } catch (_: Exception) {
                continue
            }
            var ruleAdded = 0
            for (hit in hits) {
                if (ruleAdded >= rule.matchLimit) break
                val key = "${hit.board}/${hit.no}"
                if (key in watched) continue
                addFromCatalog(hit, rule.labelPrefix)
                ruleAdded++
                added++
            }
        }
        return added
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepo.update(transform)
    }

    suspend fun exportWatchListJson(): String {
        return WatchListIo.exportJson(db.threads().getAll(), db.rules().getAll())
    }

    suspend fun importWatchListJson(text: String): Pair<Int, Int> {
        val result = WatchListIo.importJson(text)
        var threadsAdded = 0
        for (t in result.threads) {
            val existing = db.threads().findByBoardThread(t.board, t.threadNo)
            if (existing == null) {
                db.threads().upsert(t)
                threadsAdded++
            }
        }
        var rulesAdded = 0
        val existingRules = db.rules().getAll().map { "${it.board}|${it.query}" }.toSet()
        for (r in result.rules) {
            val key = "${r.board}|${r.query}"
            if (key !in existingRules) {
                db.rules().upsert(r)
                rulesAdded++
            }
        }
        return threadsAdded to rulesAdded
    }
}
