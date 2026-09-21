package com.threadsyphon.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.threadsyphon.android.ThreadSyphonApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WatchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? ThreadSyphonApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent?.action) {
                    ACTION_PAUSE_ALL -> {
                        app.repository.pauseAll()
                        WatchService.stop(context)
                    }
                    ACTION_CHECK_ALL -> {
                        app.repository.startAll()
                        app.repository.runDueChecks()
                        WatchService.start(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.threadsyphon.android.action.PAUSE_ALL"
        const val ACTION_CHECK_ALL = "com.threadsyphon.android.action.CHECK_ALL"
    }
}
