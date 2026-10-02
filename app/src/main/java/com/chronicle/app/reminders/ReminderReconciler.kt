package com.chronicle.app.reminders

import com.chronicle.app.data.TaskRepository

/**
 * Brings the alarm/notification world back in line with the tasks table.
 * Runs at app start, after boot, and on TIME_SET / TIMEZONE_CHANGED /
 * DATE_CHANGED — every place where alarms may have been lost, skipped, or
 * shifted under the app's feet.
 *
 * Three repairs, all idempotent:
 *  - past-due, never-fired reminders deliver NOW (missed while off/dead);
 *  - future reminders are re-armed (alarms don't survive reboot);
 *  - a reminder that already delivered but was pushed back into the future
 *    (edited or un-completed) clears its delivery marker and re-arms.
 *
 * [nowMs] is injectable so the logic is JVM-testable without a clock.
 */
class ReminderReconciler(
    private val tasks: TaskRepository,
    private val reminders: ReminderGateway,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun reconcile() {
        val now = nowMs()

        // Missed the window entirely — surface it late rather than never.
        // Claim before posting: notifiedAt is the once-only dedupe, so a
        // receiver racing this loop can't double-notify.
        for (task in tasks.pendingRemindersDue(now)) {
            if (tasks.markNotified(task.id, now)) {
                reminders.notifyTask(task.id, task.title)
            }
        }

        // Still ahead of us — (re-)arm. A non-null notifiedAt on a future
        // reminder means it fired once and was then moved: clear the marker
        // so the new time can claim its own delivery.
        for (task in tasks.pendingReminders(now)) {
            if (task.notifiedAt != null) tasks.clearNotified(task.id)
            val at = task.remindAt ?: continue
            reminders.scheduleTask(task.id, task.title, at)
        }
    }
}
