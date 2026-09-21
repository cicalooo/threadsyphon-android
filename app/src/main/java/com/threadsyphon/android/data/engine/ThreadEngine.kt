package com.threadsyphon.android.data.engine

import android.content.Context
import com.threadsyphon.android.data.db.DownloadedFileEntity
import com.threadsyphon.android.data.db.WatchedThreadEntity
import com.threadsyphon.android.data.model.AppSettings
import com.threadsyphon.android.data.model.Constants
import com.threadsyphon.android.data.model.DownloadLocation
import com.threadsyphon.android.data.model.WatchStatus
import com.threadsyphon.android.data.model.wantMedia
import com.threadsyphon.android.data.network.FourChanClient
import com.threadsyphon.android.data.network.SharedLimiters
import com.threadsyphon.android.data.network.ThreadGoneException
import com.threadsyphon.android.util.Md5
import com.threadsyphon.android.util.NetworkMonitor
import com.threadsyphon.android.util.StorageHelper
import org.json.JSONObject
import java.io.File

data class CheckResult(
    val entity: WatchedThreadEntity,
    val newDownloads: Int,
    val subject: String,
    val archivedOrGone: Boolean = false,
)

/**
 * One-shot check + download cycle for a watched thread (port of Windows engine.poll).
 */
class ThreadEngine(
    private val context: Context,
    private val client: FourChanClient = FourChanClient(),
) {
    fun applyRateGaps(settings: AppSettings) {
        SharedLimiters.api.setGapSeconds(settings.rateGap)
        SharedLimiters.cdn.setGapSeconds(settings.cdnGap)
    }

    suspend fun checkAndDownload(
        thread: WatchedThreadEntity,
        settings: AppSettings,
        knownKeys: Set<String>,
        onStatus: suspend (WatchStatus, String) -> Unit = { _, _ -> },
        onFileSaved: suspend (DownloadedFileEntity) -> Unit = {},
    ): CheckResult {
        applyRateGaps(settings)
        if (!NetworkMonitor.mayDownload(context, settings.allowMobileData)) {
            return CheckResult(
                thread.copy(
                    status = WatchStatus.Watching.name,
                    lastError = "Waiting for Wi‑Fi (mobile data blocked)",
                ),
                0,
                thread.subject,
            )
        }

        onStatus(WatchStatus.Downloading, "")
        val folder = StorageHelper.threadFolder(
            context,
            thread.board,
            thread.threadNo,
            settings.downloadLocation,
        )
        if (StorageHelper.isLowStorage(folder)) {
            return CheckResult(
                thread.copy(
                    status = WatchStatus.StoppedLowStorage.name,
                    lastError = "Low storage — free space and retry",
                    lastCheckedAt = System.currentTimeMillis(),
                    nextCheckAt = 0,
                ),
                0,
                thread.subject,
            )
        }

        val payload = try {
            client.fetchThreadJson(thread.board, thread.threadNo)
        } catch (e: ThreadGoneException) {
            return CheckResult(
                thread.copy(
                    status = WatchStatus.Complete.name,
                    lastError = "Thread 404 / gone",
                    lastCheckedAt = System.currentTimeMillis(),
                    nextCheckAt = 0,
                ),
                0,
                thread.subject,
                archivedOrGone = true,
            )
        } catch (e: Exception) {
            return CheckResult(
                thread.copy(
                    status = WatchStatus.Error.name,
                    lastError = e.message ?: "Fetch failed",
                    lastCheckedAt = System.currentTimeMillis(),
                ),
                0,
                thread.subject,
            )
        }

        val posts = payload.optJSONArray("posts")
        val op = posts?.optJSONObject(0)
        val subject = op?.let { stripHtml(it.optString("sub", "")) }.orEmpty()
            .ifBlank { thread.subject }

        val archived = op?.optInt("archived", 0) == 1 || op?.optInt("closed", 0) == 1
        var saved = 0
        var numbered = 0
        val mediaFilter = thread.mediaFilter.ifBlank { settings.mediaFilter }
        val filenameMode = thread.filenameMode.ifBlank { settings.filenameMode }
        val verifyMd5 = thread.verifyMd5
        val maxBytes = if (thread.maxFileMb > 0) thread.maxFileMb * 1024L * 1024L
        else if (settings.maxFileMb > 0) settings.maxFileMb * 1024L * 1024L
        else 0L

        if (posts != null) {
            for (i in 0 until posts.length()) {
                val post = posts.optJSONObject(i) ?: continue
                if (!post.has("tim") || !post.has("ext")) continue
                val tim = post.optLong("tim")
                val ext = post.optString("ext")
                if (!wantMedia(ext, mediaFilter)) continue
                val fsize = post.optLong("fsize", 0)
                if (maxBytes > 0 && fsize > maxBytes) continue

                val key = "${thread.board}/${thread.threadNo}/$tim"
                if (key in knownKeys) continue

                numbered++
                val displayName = resolveFilename(post, filenameMode, numbered, tim, ext)
                val part = File(folder, "$displayName.part")
                val target = File(folder, displayName)

                if (target.exists() && target.length() > 0) {
                    // Already on disk from prior run
                    val entity = DownloadedFileEntity(
                        key = key,
                        threadId = thread.id,
                        board = thread.board,
                        threadNo = thread.threadNo,
                        tim = tim,
                        filename = displayName,
                        ext = ext,
                        size = target.length(),
                        md5 = post.optString("md5", ""),
                    )
                    onFileSaved(entity)
                    saved++
                    continue
                }

                try {
                    client.downloadMedia(thread.board, tim, ext, part)
                    if (verifyMd5) {
                        val expected = post.optString("md5", "")
                        if (expected.isNotBlank()) {
                            val actual = Md5.fileMd5Base64(part)
                            if (actual != expected) {
                                part.delete()
                                continue
                            }
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    if (settings.downloadLocation == DownloadLocation.MediaStoreDownloads) {
                        StorageHelper.publishToDownloads(
                            context,
                            target,
                            thread.board,
                            thread.threadNo,
                            displayName,
                            StorageHelper.mimeForExt(ext),
                        )
                    }
                    onFileSaved(
                        DownloadedFileEntity(
                            key = key,
                            threadId = thread.id,
                            board = thread.board,
                            threadNo = thread.threadNo,
                            tim = tim,
                            filename = displayName,
                            ext = ext,
                            size = target.length(),
                            md5 = post.optString("md5", ""),
                        ),
                    )
                    saved++
                } catch (_: Exception) {
                    // Keep .part for resume; skip this file this cycle
                }
            }
        }

        val now = System.currentTimeMillis()
        val nextStatus = when {
            archived -> WatchStatus.Complete
            else -> WatchStatus.Watching
        }
        val updated = thread.copy(
            subject = subject,
            status = nextStatus.name,
            savedCount = thread.savedCount + saved,
            lastError = if (archived) "Thread archived/closed" else "",
            lastCheckedAt = now,
            nextCheckAt = if (archived) 0 else now + thread.intervalSec * 1000L,
            folderRelative = "${thread.board}/${thread.threadNo}",
        )
        onStatus(nextStatus, updated.lastError)
        return CheckResult(updated, saved, subject, archivedOrGone = archived)
    }

    private fun resolveFilename(
        post: JSONObject,
        mode: String,
        index: Int,
        tim: Long,
        ext: String,
    ): String {
        return when (mode) {
            "server" -> "$tim$ext"
            "numbered" -> "%04d%s".format(index, ext)
            else -> {
                val original = safeFilename(post.optString("filename", ""), "media")
                var name = original + ext
                // Collision: append tim
                name
            }
        }
    }
}
