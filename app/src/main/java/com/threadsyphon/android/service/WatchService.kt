package com.threadsyphon.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.threadsyphon.android.MainActivity
import com.threadsyphon.android.R
import com.threadsyphon.android.ThreadSyphonApp
import com.threadsyphon.android.data.engine.WatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-running watcher. Uses [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE]
 * because always-on thread watching does not fit the Android 15 dataSync 6h/24h budget.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: WatchRepository
    private var loopJob: Job? = null
    private var scoutJob: Job? = null
    private var activeObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        repo = (application as ThreadSyphonApp).repository
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startAsForeground(0)
        if (loopJob?.isActive != true) loopJob = scope.launch { watchLoop() }
        if (scoutJob?.isActive != true) scoutJob = scope.launch { scoutLoop() }
        if (activeObserverJob?.isActive != true) {
            activeObserverJob = scope.launch {
                repo.observeActiveCount().collectLatest { count ->
                    updateNotification(count)
                    if (count <= 0) {
                        delay(3_000)
                        stopSelfSafely()
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Android 15+ safety net. specialUse is not under the dataSync 6h limit, but if the
     * system still times us out we must stop promptly to avoid RemoteServiceException.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout startId=$startId fgsType=$fgsType — stopping cleanly")
        stopSelfSafely()
    }

    private suspend fun watchLoop() {
        while (scope.isActive) {
            try { repo.runDueChecks() } catch (_: Exception) {}
            delay(5_000)
        }
    }

    private suspend fun scoutLoop() {
        while (scope.isActive) {
            try {
                val added = repo.scoutOnce()
                if (added > 0) {
                    NotificationHelper.notifyEvent(this@WatchService, "Scout", "Added $added thread(s) from rules")
                }
            } catch (_: Exception) {}
            delay(60_000)
        }
    }

    private fun startAsForeground(activeCount: Int) {
        val notification = buildNotification(activeCount)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: specialUse; fall back to dataSync only on older if constant missing — prefer specialUse from 34
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.WATCH_NOTIFICATION_ID,
                notification,
                type,
            )
        } else {
            startForeground(NotificationHelper.WATCH_NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(activeCount: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.WATCH_NOTIFICATION_ID, buildNotification(activeCount))
    }

    private fun buildNotification(activeCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, WatchActionReceiver::class.java).setAction(WatchActionReceiver.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_watching, activeCount.coerceAtLeast(0)))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_pause_all), pause)
            .addAction(0, getString(R.string.notif_open), open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopSelfSafely() {
        loopJob?.cancel()
        scoutJob?.cancel()
        activeObserverJob?.cancel()
        loopJob = null
        scoutJob = null
        activeObserverJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WatchService"
        const val ACTION_STOP = "com.threadsyphon.android.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }
    }
}