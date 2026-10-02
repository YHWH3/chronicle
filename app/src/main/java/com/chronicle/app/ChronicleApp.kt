package com.chronicle.app

import android.app.Application
import kotlinx.coroutines.launch

class ChronicleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Catch up reminders missed while the process was dead, and re-arm
        // anything edited forward while notifications were mid-flight.
        container.appScope.launch {
            container.taskRepository.reconcileReminders(container.reminderGateway)
        }
    }
}
