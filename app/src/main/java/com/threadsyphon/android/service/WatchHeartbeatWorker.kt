package com.threadsyphon.android.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic (~15m) backup that restarts WatchService if it should still be watching. */
class WatchHeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "heartbeat worker run")
        return try {
            WatchKeepAliveReceiver.ensureWatching(applicationContext, "WorkManager")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WatchHeartbeatWorker"
    }
}
