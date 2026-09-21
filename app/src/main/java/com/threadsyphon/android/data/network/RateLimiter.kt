package com.threadsyphon.android.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Process-wide courtesy gap so multiple watchers don't burst the API/CDN.
 */
class RateLimiter(initialGapMs: Long = 1000L) {
    @Volatile
    var gapMs: Long = initialGapMs
        private set

    private val mutex = Mutex()
    private var nextAt = 0L

    fun setGapSeconds(seconds: Float) {
        gapMs = (seconds.coerceAtLeast(0.05f) * 1000).toLong()
    }

    suspend fun waitTurn() {
        val delayMs = mutex.withLock {
            val now = System.nanoTime() / 1_000_000L
            val wait = max(0L, nextAt - now)
            nextAt = max(nextAt, now) + gapMs
            wait
        }
        if (delayMs > 0) delay(delayMs)
    }
}

object SharedLimiters {
    val api = RateLimiter(1000L)
    val cdn = RateLimiter(250L)
}
