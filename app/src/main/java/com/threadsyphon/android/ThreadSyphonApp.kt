package com.threadsyphon.android

import android.app.Application
import com.threadsyphon.android.data.engine.WatchRepository
import com.threadsyphon.android.service.NotificationHelper
import com.threadsyphon.android.service.WatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThreadSyphonApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: WatchRepository
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        repository = WatchRepository(this)
        applicationScope.launch(Dispatchers.IO) {
            repository.observeThreads().collect { list ->
                val needs = list.any {
                    it.status == "Ready" || it.status == "Watching" || it.status == "Downloading"
                }
                if (needs) WatchService.start(this@ThreadSyphonApp)
            }
        }
    }
}
