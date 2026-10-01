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
 * Schedules reminder alarms. Uses inexact alarms ([AlarmManager.setAndAllowWhileIdle])
 * so no SCHEDULE_EXACT_ALARM permission is needed — reminders may arrive a few
 * minutes late under deep doze, which suits a journal.
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
        taskIntent(context, taskId, "", REQUEST_TASK_OFFSET + taskId.toInt()).cancel()
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
        PendingIntent.getBroadcast(
            context,
            REQUEST_DAILY,
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_DAILY_NUDGE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).cancel()
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
