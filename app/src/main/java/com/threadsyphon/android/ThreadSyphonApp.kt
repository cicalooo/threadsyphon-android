package com.threadsyphon.android

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.service.KeepAliveScheduler
import com.threadsyphon.android.service.NotificationHelper
import com.threadsyphon.android.service.WatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThreadSyphonApp : Application(), Configuration.Provider {
    companion object {
        private const val TAG = "ThreadSyphonApp"
        lateinit var instance: ThreadSyphonApp
            private set
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: WatchRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.ensureChannels(this)
        repository = WatchRepository(this)
        applicationScope.launch(Dispatchers.IO) {
            repository.migrateLegacyAppExternalToSharedRootOnce()
        }
        applicationScope.launch(Dispatchers.IO) {
            repository.observeThreads().collect { list ->
                val needs = list.any {
                    !it.hidden && (
                        it.status == "Ready" ||
                            it.status == "Watching" ||
                            it.status == "Downloading" ||
                            it.status == "Error"
                        )
                }
                // Also keep alive when scout rules are enabled (checked separately on start).
                if (needs) {
                    WatchService.start(this@ThreadSyphonApp)
                    KeepAliveScheduler.scheduleHeartbeat(this@ThreadSyphonApp)
                }
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                if (repository.needsWatching()) {
                    Log.i(TAG, "onCreate: needsWatching — ensuring service + heartbeat")
                    WatchService.start(this@ThreadSyphonApp)
                    KeepAliveScheduler.scheduleHeartbeat(this@ThreadSyphonApp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "onCreate ensure failed: ${e.message}")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
