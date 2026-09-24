package com.threadsyphon.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.threadsyphon.android.ThreadSyphonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot / package-replaced / alarm / WorkManager path to re-ensure WatchService.
 * Starting FGS from these receivers is an allowed background start reason on modern Android.
 */
class WatchKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "onReceive action=$action")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureWatching(context.applicationContext, action)
            } catch (e: Exception) {
                Log.w(TAG, "ensureWatching failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WatchKeepAlive"
        const val ACTION_HEARTBEAT = "com.threadsyphon.android.action.KEEPALIVE_HEARTBEAT"
        const val ACTION_RESTART = "com.threadsyphon.android.action.KEEPALIVE_RESTART"

        suspend fun ensureWatching(context: Context, reason: String) {
            val appCtx = context.applicationContext
            val needs = try {
                val app = appCtx as? ThreadSyphonApp
                app?.repository?.needsWatching() == true
            } catch (e: Exception) {
                Log.w(TAG, "needsWatching error: ${e.message}")
                false
            }
            Log.i(TAG, "ensureWatching reason=$reason needs=$needs")
            if (needs) {
                WatchService.start(appCtx)
                KeepAliveScheduler.scheduleHeartbeat(appCtx)
            } else {
                KeepAliveScheduler.cancelAll(appCtx)
            }
        }
    }
}
