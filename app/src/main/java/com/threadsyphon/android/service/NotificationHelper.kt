package com.threadsyphon.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.threadsyphon.android.R

object NotificationHelper {
    const val CHANNEL_WATCH = "watch"
    const val CHANNEL_EVENTS = "events"
    const val WATCH_NOTIFICATION_ID = 1001
    private var eventId = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, context.getString(R.string.channel_watch_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_watch_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.channel_events_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_events_desc)
            },
        )
    }

    fun notifyEvent(context: Context, title: String, body: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(eventId++, n)
    }
}
