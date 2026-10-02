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

    /**
     * Task reminders keep the original channel id — channel settings are
     * locked at creation, so renaming would strand existing installs on a
     * channel they already tuned. The daily nudge gets its own silent
     * channel; a nudge should never buzz.
     */
    const val CHANNEL_TASKS = "chronicle_reminders"
    const val CHANNEL_NUDGE = "chronicle_nudge"

    /**
     * Extra on the tap PendingIntent telling the host which surface to open.
     * The manifest is frozen — a "mark done" action would need a new
     * receiver — so tapping simply opens the app on the tasks route; the
     * route-handling half lives in navigation code.
     */
    const val EXTRA_ROUTE = "chronicle.route"
    const val ROUTE_TASKS = "tasks"
    const val ROUTE_TODAY = "today"

    fun ensureChannels(context: Context) {
        val manager = notificationManager(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TASKS,
                "Reminders",
                // Default importance — an intention nudge is not HIGH, and
                // channel importance is immutable after creation anyway.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminders for your intentions"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NUDGE,
                "Daily nudge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "The quiet daily writing prompt"
                // Silent by design — sound and vibration stay off.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun notifyTask(context: Context, taskId: Long, title: String) {
        notify(
            context,
            taskId.toInt(),
            CHANNEL_TASKS,
            "A note to yourself",
            title,
            route = ROUTE_TASKS,
        )
    }

    fun notifyDailyNudge(context: Context) {
        notify(
            context,
            DAILY_NUDGE_ID,
            CHANNEL_NUDGE,
            "Chronicle",
            "A quiet moment — what happened today?",
            route = ROUTE_TODAY,
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        body: String,
        route: String,
    ) {
        ensureChannels(context)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        // Reuse a running MainActivity instead of stacking a second one.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(EXTRA_ROUTE, route)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
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
