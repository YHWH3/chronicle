package com.chronicle.app.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * The alarm/notification side effects of keeping intentions — injectable so
 * ViewModels stay free of Android framework calls and testable on the JVM.
 */
interface ReminderGateway {
    fun scheduleTask(taskId: Long, title: String, atMillis: Long)
    fun cancelTask(taskId: Long)
    /** Posts the reminder notification right now — used by missed-fire catch-up. */
    fun notifyTask(taskId: Long, title: String)
    fun cancelNotification(taskId: Long)
    fun scheduleDailyNudge(minutesAfterMidnight: Int)
    fun cancelDailyNudge()
    /** False when POST_NOTIFICATIONS is denied — a stored reminder can never fire. */
    fun notificationsEnabled(): Boolean
}

class AndroidReminderGateway(context: Context) : ReminderGateway {

    private val appContext = context.applicationContext

    override fun scheduleTask(taskId: Long, title: String, atMillis: Long) =
        ReminderScheduler.scheduleTask(appContext, taskId, title, atMillis)

    override fun cancelTask(taskId: Long) =
        ReminderScheduler.cancelTask(appContext, taskId)

    override fun notifyTask(taskId: Long, title: String) =
        Notifier.notifyTask(appContext, taskId, title)

    override fun cancelNotification(taskId: Long) =
        Notifier.cancel(appContext, taskId)

    override fun scheduleDailyNudge(minutesAfterMidnight: Int) =
        ReminderScheduler.scheduleDailyNudge(appContext, minutesAfterMidnight)

    override fun cancelDailyNudge() =
        ReminderScheduler.cancelDailyNudge(appContext)

    override fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()
}
