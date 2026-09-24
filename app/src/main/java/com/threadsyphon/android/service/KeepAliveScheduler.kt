package com.threadsyphon.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Reschedule WatchService after OEM kill / FGS timeout / reboot.
 * AlarmManager exact-while-idle for near-term restart; WorkManager 15m heartbeat as backup.
 */
object KeepAliveScheduler {
    private const val TAG = "KeepAliveScheduler"
    const val HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L
    const val RESTART_DELAY_MS = 5_000L
    private const val WORK_NAME = "threadsyphon_watch_heartbeat"
    private const val REQ_HEARTBEAT = 41001
    private const val REQ_RESTART = 41002

    fun scheduleHeartbeat(context: Context) {
        val app = context.applicationContext
        scheduleAlarm(app, REQ_HEARTBEAT, HEARTBEAT_INTERVAL_MS, WatchKeepAliveReceiver.ACTION_HEARTBEAT)
        runCatching {
            val req = PeriodicWorkRequestBuilder<WatchHeartbeatWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }.onFailure { Log.w(TAG, "WorkManager heartbeat enqueue failed: ${it.message}") }
        Log.i(TAG, "heartbeat scheduled (~${HEARTBEAT_INTERVAL_MS / 60_000}m)")
    }

    fun scheduleImmediateRestart(context: Context, reason: String) {
        val app = context.applicationContext
        Log.w(TAG, "scheduleImmediateRestart reason=$reason")
        scheduleAlarm(app, REQ_RESTART, RESTART_DELAY_MS, WatchKeepAliveReceiver.ACTION_RESTART)
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(app, REQ_HEARTBEAT, WatchKeepAliveReceiver.ACTION_HEARTBEAT))
        am.cancel(pending(app, REQ_RESTART, WatchKeepAliveReceiver.ACTION_RESTART))
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
        Log.i(TAG, "keep-alive cancelled")
    }

    private fun scheduleAlarm(context: Context, requestCode: Int, delayMs: Long, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, requestCode, action)
        val trigger = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, falling back: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }
    }

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, WatchKeepAliveReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
