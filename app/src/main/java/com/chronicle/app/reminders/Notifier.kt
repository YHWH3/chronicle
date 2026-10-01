package com.chronicle.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chronicle.app.MainActivity
import com.chronicle.app.R

object Notifier {

    const val CHANNEL_ID = "chronicle_reminders"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Task reminders and the daily writing nudge"
        }
        notificationManager(context).createNotificationChannel(channel)
    }

    fun notifyTask(context: Context, taskId: Long, title: String) {
        notify(context, taskId.toInt(), "A note to yourself", title)
    }

    fun notifyDailyNudge(context: Context) {
        notify(
            context,
            DAILY_NUDGE_ID,
            "Chronicle",
            "A quiet moment — what happened today?",
        )
    }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            notificationManager(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the preference UI guides the user.
        }
    }

    fun cancel(context: Context, taskId: Long) {
        notificationManager(context).cancel(taskId.toInt())
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    const val DAILY_NUDGE_ID = -7001
}
