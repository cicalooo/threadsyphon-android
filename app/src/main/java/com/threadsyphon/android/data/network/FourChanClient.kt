package com.threadsyphon.android.data.network

import com.threadsyphon.android.data.model.CatalogThread
import com.threadsyphon.android.data.model.Constants
import com.threadsyphon.android.data.engine.stripHtml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FourChanClient(
    private val client: OkHttpClient = defaultClient(),
) {
    private val catalogCache = ConcurrentHashMap<String, Pair<Long, List<CatalogThread>>>()
    private val catalogTtlMs = 45_000L

    suspend fun fetchThreadJson(board: String, threadNo: Long): JSONObject {
        SharedLimiters.api.waitTurn()
        val url = "${Constants.API_BASE}/$board/thread/$threadNo.json"
        val body = getBytes(url, accept = "application/json")
        return JSONObject(String(body, Charsets.UTF_8))
    }

    suspend fun fetchCatalog(board: String, force: Boolean = false): List<CatalogThread> {
        val key = board.lowercase()
        val now = System.currentTimeMillis()
        if (!force) {
            catalogCache[key]?.let { (ts, list) ->
                if (now - ts < catalogTtlMs) return list
            }
        }
        SharedLimiters.api.waitTurn()
        val url = "${Constants.API_BASE}/$key/catalog.json"
        val body = getBytes(url, accept = "application/json")
        val parsed = parseCatalog(key, String(body, Charsets.UTF_8))
        catalogCache[key] = now to parsed
        return parsed
    }

    /**
     * Download media with optional Range resume into [destFile].
     * Returns final size written.
     */
    suspend fun downloadMedia(
        board: String,
        tim: Long,
        ext: String,
        destFile: java.io.File,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        SharedLimiters.cdn.waitTurn()
        val url = "${Constants.CDN_BASE}/$board/$tim$ext"
        val existing = if (destFile.exists()) destFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .get()
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=$existing-")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            when (response.code) {
                200 -> {
                    // Full body (server ignored Range or fresh)
                    destFile.outputStream().use { out ->
                        response.body?.byteStream()?.copyToWithProgress(out, onProgress)
                    }
                }
                206 -> {
                    destFile.appendOutputStream().use { out ->
                        var written = existing
                        response.body?.byteStream()?.let { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                onProgress?.invoke(written)
                                if (written > Constants.MAX_DOWNLOAD_BYTES) {
                                    throw IllegalStateException("Download exceeds size limit")
                                }
                            }
                        }
                    }
                }
                404 -> throw MediaGoneException("Media 404: $url")
                else -> throw IllegalStateException("CDN HTTP ${response.code} for $url")
            }
        }
        return destFile.length()
    }

    private fun getBytes(url: String, accept: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Constants.USER_AGENT)
            .header("Accept", accept)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ThreadGoneException("404: $url")
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: $url")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.size > Constants.MAX_JSON_BYTES) {
                throw IllegalStateException("Response too large")
            }
            return bytes
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun parseCatalog(board: String, json: String): List<CatalogThread> {
            val root = JSONArray(json)
            val out = ArrayList<CatalogThread>()
            for (i in 0 until root.length()) {
                val page = root.optJSONObject(i) ?: continue
                val threads = page.optJSONArray("threads") ?: continue
                for (j in 0 until threads.length()) {
                    val row = threads.optJSONObject(j) ?: continue
                    val no = row.optLong("no", -1)
                    if (no <= 0) continue
                    out.add(
                        CatalogThread(
                            board = board,
                            no = no,
                            title = stripHtml(row.optString("sub", "")),
                            body = stripHtml(row.optString("com", "")),
                            replies = row.optInt("replies", 0).coerceAtLeast(0),
                            images = row.optInt("images", 0).coerceAtLeast(0),
                            sticky = row.optInt("sticky", 0) != 0,
                            closed = row.optInt("closed", 0) != 0,
                            time = row.optLong("time", 0).coerceAtLeast(0),
                            semanticUrl = row.optString("semantic_url", ""),
                        ),
                    )
                }
            }
            return out
        }
    }
}

class ThreadGoneException(message: String) : Exception(message)
class MediaGoneException(message: String) : Exception(message)

private fun java.io.InputStream.copyToWithProgress(
    out: java.io.OutputStream,
    onProgress: ((Long) -> Unit)?,
) {
    val buf = ByteArray(64 * 1024)
    var written = 0L
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        written += n
        onProgress?.invoke(written)
        if (written > Constants.MAX_DOWNLOAD_BYTES) {
            throw IllegalStateException("Download exceeds size limit")
        }
    }
}

private fun java.io.File.appendOutputStream(): java.io.OutputStream =
    java.io.FileOutputStream(this, true)
