package com.chronicle.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules reminder alarms. Deliberately inexact:
 * [AlarmManager.setAndAllowWhileIdle] needs no SCHEDULE_EXACT_ALARM
 * permission (better Play policy, no permission prompt), and a nudge-
 * style reminder tolerates minutes-level drift. Timeliness for reminders
 * that missed their window entirely is handled by [ReminderReconciler]'s
 * catch-up pass, not by exactness here.
 */
object ReminderScheduler {

    const val EXTRA_TASK_ID = "chronicle.task_id"
    const val EXTRA_TASK_TITLE = "chronicle.task_title"
    const val EXTRA_DAILY_NUDGE = "chronicle.daily_nudge"

    private const val REQUEST_TASK_OFFSET = 100_000
    private const val REQUEST_DAILY = 200_001

    fun scheduleTask(context: Context, taskId: Long, title: String, atMillis: Long) {
        val intent = taskIntent(context, taskId, title, REQUEST_TASK_OFFSET + taskId.toInt())
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            atMillis,
            intent,
        )
    }

    fun cancelTask(context: Context, taskId: Long) {
        val pending = taskIntent(context, taskId, "", REQUEST_TASK_OFFSET + taskId.toInt())
        // PendingIntent.cancel() alone does NOT drop the alarm — the system
        // keeps it and fires an intent that resolves to nothing. Cancel the
        // alarm first, then release the PendingIntent.
        alarmManager(context).cancel(pending)
        pending.cancel()
    }

    /** Daily writing nudge at [minutes] past midnight, local time. */
    fun scheduleDailyNudge(context: Context, minutes: Int) {
        val now = LocalDateTime.now()
        var fire = LocalDateTime.of(LocalDate.now(), LocalTime.of(minutes / 60, minutes % 60))
        if (fire <= now) fire = fire.plusDays(1)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_DAILY,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_DAILY_NUDGE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact daily cadence — the system may batch it with other alarms.
        alarmManager(context).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            fire.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            pending,
        )
    }

    fun cancelDailyNudge(context: Context) {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_DAILY,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_DAILY_NUDGE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Same contract as cancelTask: alarm first, then the PendingIntent.
        alarmManager(context).cancel(pending)
        pending.cancel()
    }

    private fun taskIntent(context: Context, taskId: Long, title: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TASK_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
