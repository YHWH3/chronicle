package com.chronicle.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chronicle.app.ChronicleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Re-arms reminders after a reboot — alarms don't survive restarts. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as ChronicleApp).container
                val now = System.currentTimeMillis()
                for (task in container.taskRepository.pendingReminders(now)) {
                    ReminderScheduler.scheduleTask(context, task.id, task.title, task.remindAt!!)
                }
                val prefs = container.prefsRepository.prefs.first()
                if (prefs.dailyNudgeEnabled) {
                    ReminderScheduler.scheduleDailyNudge(context, prefs.dailyNudgeMinutes)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
