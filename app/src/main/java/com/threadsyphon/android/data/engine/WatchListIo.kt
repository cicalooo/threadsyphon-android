package com.threadsyphon.android.data.engine

import com.threadsyphon.android.data.db.WatchRuleEntity
import com.threadsyphon.android.data.db.WatchedThreadEntity
import com.threadsyphon.android.data.model.WatchStatus
import com.threadsyphon.android.data.model.newId
import org.json.JSONArray
import org.json.JSONObject

object WatchListIo {
    const val FORMAT_VERSION = 1

    fun exportJson(threads: List<WatchedThreadEntity>, rules: List<WatchRuleEntity>): String {
        val root = JSONObject()
        root.put("format", "threadsyphon-watchlist")
        root.put("version", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        val tArr = JSONArray()
        for (t in threads) {
            tArr.put(
                JSONObject()
                    .put("url", t.url)
                    .put("board", t.board)
                    .put("threadNo", t.threadNo)
                    .put("label", t.label)
                    .put("subject", t.subject)
                    .put("intervalSec", t.intervalSec)
                    .put("mediaFilter", t.mediaFilter)
                    .put("filenameMode", t.filenameMode)
                    .put("maxFileMb", t.maxFileMb)
                    .put("verifyMd5", t.verifyMd5)
                    .put("status", t.status)
                    .put("autoStart", t.autoStart)
                    .put("hidden", t.hidden),
            )
        }
        root.put("threads", tArr)
        val rArr = JSONArray()
        for (r in rules) {
            rArr.put(
                JSONObject()
                    .put("name", r.name)
                    .put("board", r.board)
                    .put("query", r.query)
                    .put("enabled", r.enabled)
                    .put("intervalSec", r.intervalSec)
                    .put("matchLimit", r.matchLimit)
                    .put("labelPrefix", r.labelPrefix)
                    .put("notify", r.notify),
            )
        }
        root.put("rules", rArr)
        return root.toString(2)
    }

    data class ImportResult(
        val threads: List<WatchedThreadEntity>,
        val rules: List<WatchRuleEntity>,
    )

    fun importJson(text: String): ImportResult {
        val root = JSONObject(text)
        val threads = mutableListOf<WatchedThreadEntity>()
        val tArr = root.optJSONArray("threads") ?: JSONArray()
        for (i in 0 until tArr.length()) {
            val o = tArr.optJSONObject(i) ?: continue
            val board = o.optString("board").lowercase()
            val threadNo = o.optLong("threadNo", 0)
            if (board.isBlank() || threadNo <= 0) continue
            val url = o.optString("url").ifBlank { "https://boards.4chan.org/$board/thread/$threadNo" }
            val statusRaw = o.optString("status", WatchStatus.Ready.name)
            val status = statusRaw.takeIf { runCatching { WatchStatus.valueOf(it) }.isSuccess }
                ?: WatchStatus.Ready.name
            threads.add(
                WatchedThreadEntity(
                    id = newId(),
                    url = url,
                    board = board,
                    threadNo = threadNo,
                    label = o.optString("label", ""),
                    subject = o.optString("subject", ""),
                    intervalSec = o.optInt("intervalSec", 30).coerceIn(15, 3600),
                    mediaFilter = o.optString("mediaFilter", "all"),
                    filenameMode = o.optString("filenameMode", "original"),
                    maxFileMb = o.optInt("maxFileMb", 0).coerceAtLeast(0),
                    verifyMd5 = o.optBoolean("verifyMd5", true),
                    status = if (status == WatchStatus.Downloading.name || status == WatchStatus.Watching.name) {
                        WatchStatus.Ready.name
                    } else status,
                    autoStart = o.optBoolean("autoStart", true),
                    hidden = o.optBoolean("hidden", false),
                    folderRelative = "$board/$threadNo",
                ),
            )
        }
        val rules = mutableListOf<WatchRuleEntity>()
        val rArr = root.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rArr.length()) {
            val o = rArr.optJSONObject(i) ?: continue
            val board = o.optString("board").lowercase()
            val query = o.optString("query")
            if (board.isBlank() || query.isBlank()) continue
            rules.add(
                WatchRuleEntity(
                    id = newId(),
                    name = o.optString("name", "/$board/ rule"),
                    board = board,
                    query = query,
                    enabled = o.optBoolean("enabled", true),
                    intervalSec = o.optInt("intervalSec", 120).coerceIn(60, 3600),
                    matchLimit = o.optInt("matchLimit", 5).coerceIn(1, 50),
                    labelPrefix = o.optString("labelPrefix", ""),
                    notify = o.optBoolean("notify", true),
                ),
            )
        }
        return ImportResult(threads, rules)
    }
}
