package com.threadsyphon.android.data.model

import java.net.URI
import java.util.UUID

object Constants {
    val ALLOWED_HOSTS = setOf(
        "boards.4chan.org", "boards.4channel.org",
        "www.4chan.org", "www.4channel.org",
        "4chan.org", "4channel.org",
    )
    val FILENAME_MODES = listOf("original", "server", "numbered")
    val MEDIA_FILTERS = listOf("all", "images", "video")
    val MEDIA_FILTER_LABELS = mapOf(
        "all" to "ALL",
        "images" to "images only",
        "video" to "video only",
    )
    val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
    val VIDEO_EXTS = setOf(".webm", ".mp4")
    const val USER_AGENT = "threadsyphon-android/1.0.7 (+mobile thread archiver; respectful polling)"
    const val API_BASE = "https://a.4cdn.org"
    const val CDN_BASE = "https://i.4cdn.org"
    const val FREE_SPACE_RESERVE_BYTES = 8L * 1024 * 1024
    const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
    const val MAX_JSON_BYTES = 16 * 1024 * 1024
    const val SHARED_ROOT_FOLDER = "threadsyphon"
}

enum class WatchStatus { Ready, Watching, Downloading, Paused, Complete, Error, StoppedLowStorage }

/**
 * Where media lands on disk.
 * Default is [SharedRoot] — visible `Internal storage/threadsyphon`.
 */
enum class DownloadLocation {
    SharedRoot,
    CustomPath,
    AppExternal,
    MediaStoreDownloads,
}

data class AppSettings(
    val defaultInterval: Int = 30,
    val notifications: Boolean = true,
    val mediaFilter: String = "all",
    val maxFileMb: Int = 0,
    val filenameMode: String = "original",
    val verifyMd5: Boolean = true,
    val rateGap: Float = 1.0f,
    val cdnGap: Float = 0.25f,
    val wifiOnly: Boolean = true,
    val allowMobileData: Boolean = false,
    val downloadLocation: DownloadLocation = DownloadLocation.SharedRoot,
    /** Absolute filesystem path when [downloadLocation] is [DownloadLocation.CustomPath]. */
    val customRootPath: String = "",
    /** Persistable SAF tree URI (optional companion to [customRootPath]). */
    val customRootUri: String = "",
    val autoHideFinished: Boolean = true,
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean = false,
    val followSystemTheme: Boolean = true,
    val scoutIntervalSec: Int = 120,
) {
    fun normalized(): AppSettings = copy(
        defaultInterval = defaultInterval.coerceIn(15, 3600),
        mediaFilter = mediaFilter.takeIf { it in Constants.MEDIA_FILTERS } ?: "all",
        filenameMode = filenameMode.takeIf { it in Constants.FILENAME_MODES } ?: "original",
        maxFileMb = maxFileMb.coerceAtLeast(0),
        rateGap = rateGap.coerceIn(0.25f, 10f),
        cdnGap = cdnGap.coerceIn(0.05f, 5f),
        wifiOnly = if (allowMobileData) false else wifiOnly,
        scoutIntervalSec = scoutIntervalSec.coerceIn(60, 3600),
    )
}

data class ParsedThreadUrl(val board: String, val threadNo: Long, val canonicalUrl: String)

fun parseThreadUrl(url: String): ParsedThreadUrl {
    var raw = url.trim()
    require(raw.isNotEmpty()) { "Paste a 4chan thread URL." }
    if (!raw.contains("://")) raw = "https://$raw"
    val uri = URI(raw)
    val host = (uri.host ?: "").lowercase()
    require(host in Constants.ALLOWED_HOSTS) { "Only 4chan thread URLs are supported." }
    val path = uri.path ?: ""
    val match = Regex("""^/([a-z0-9]+)/thread/(\d+)(?:/[^/]*)?/?$""", RegexOption.IGNORE_CASE)
        .matchEntire(path) ?: throw IllegalArgumentException("That does not look like a 4chan thread URL.")
    val board = match.groupValues[1].lowercase()
    val threadNo = match.groupValues[2].toLong()
    require(threadNo > 0) { "Thread number must be a positive integer." }
    return ParsedThreadUrl(board, threadNo, "https://boards.4chan.org/$board/thread/$threadNo")
}

fun extractThreadUrlFromText(text: String): String? {
    val regex = Regex(
        """https?://(?:boards\.)?(?:4chan|4channel)\.org/[a-z0-9]+/thread/\d+[^\s]*""",
        RegexOption.IGNORE_CASE,
    )
    regex.find(text)?.value?.let { return it }
    val bare = Regex(
        """(?:boards\.)?(?:4chan|4channel)\.org/[a-z0-9]+/thread/\d+[^\s]*""",
        RegexOption.IGNORE_CASE,
    )
    return bare.find(text)?.value?.let { "https://$it" }
}

fun wantMedia(extension: String, mediaFilter: String): Boolean {
    val ext = extension.lowercase().let { if (it.startsWith(".")) it else ".$it" }
    return when (mediaFilter) {
        "images" -> ext in Constants.IMAGE_EXTS
        "video" -> ext in Constants.VIDEO_EXTS
        else -> true
    }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "")

fun thumbUrl(board: String, tim: Long): String? =
    if (tim > 0) "${Constants.CDN_BASE}/$board/${tim}s.jpg" else null

data class CatalogThread(
    val board: String,
    val no: Long,
    val title: String,
    val body: String,
    val replies: Int,
    val images: Int,
    val sticky: Boolean,
    val closed: Boolean,
    val time: Long,
    val semanticUrl: String = "",
    val tim: Long = 0L,
) {
    val url: String get() = "https://boards.4chan.org/$board/thread/$no"
    val shortId: String get() = "/$board/$no"
    val displayTitle: String
        get() = title.ifBlank {
            body.take(80).let { if (body.length > 80) "$it…" else it }
        }.ifBlank { shortId }
    val thumbnailUrl: String? get() = thumbUrl(board, tim)
}
