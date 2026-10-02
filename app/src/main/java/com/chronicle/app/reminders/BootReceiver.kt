package com.chronicle.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chronicle.app.ChronicleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Reconciles reminders after anything that invalidates alarms: a reboot
 * (alarms don't survive restarts) or a time/timezone/date change (a wall-
 * clock reminder may now be early, late, or already past). The reconcile
 * pass fires missed notifications, re-arms future ones, and repairs
 * deliveries whose time was edited forward — one code path for all of it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as ChronicleApp).container
                ReminderReconciler(
                    container.taskRepository,
                    container.reminderGateway,
                ).reconcile()
                val prefs = container.prefsRepository.prefs.first()
                if (prefs.dailyNudgeEnabled) {
                    ReminderScheduler.scheduleDailyNudge(context, prefs.dailyNudgeMinutes)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,       // android.intent.action.TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )
    }
}
