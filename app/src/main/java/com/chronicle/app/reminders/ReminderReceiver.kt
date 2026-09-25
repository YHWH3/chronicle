package com.chronicle.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chronicle.app.ChronicleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.getBooleanExtra(ReminderScheduler.EXTRA_DAILY_NUDGE, false)) {
                    Notifier.notifyDailyNudge(context)
                    // Re-arm for the next day — inexact alarms don't repeat.
                    val prefs = (context.applicationContext as ChronicleApp).container.prefsRepository
                    var minutes = 20 * 60
                    prefs.prefs.collect { minutes = it.dailyNudgeMinutes }
                    ReminderScheduler.scheduleDailyNudge(context, minutes)
                    return@launch
                }

                val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
                if (taskId <= 0L) return@launch
                val repo = (context.applicationContext as ChronicleApp).container.taskRepository
                val task = repo.byId(taskId) ?: return@launch
                if (!task.done) {
                    Notifier.notifyTask(
                        context,
                        taskId,
                        task.title.ifBlank { intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TITLE).orEmpty() },
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
