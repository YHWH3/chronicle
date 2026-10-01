package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.core.UiMessage
import com.chronicle.app.core.UiMessageBus
import com.chronicle.app.data.TaskRepository
import com.chronicle.app.data.db.TaskEntity
import com.chronicle.app.reminders.ReminderGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

// ————— Tasks —————

data class TasksUiState(
    val open: List<TaskEntity> = emptyList(),
    val done: List<TaskEntity> = emptyList(),
    val draft: String = "",
    val draftReminder: ReminderChoice = ReminderChoice.NONE,
    /** An add is in flight — guards against double-taps duplicating tasks. */
    val adding: Boolean = false,
)

enum class ReminderChoice(val label: String) {
    NONE("No reminder"),
    IN_ONE_HOUR("In an hour"),
    TONIGHT("Tonight"),
    TOMORROW("Tomorrow"),
    ;

    fun resolve(now: LocalDateTime): LocalDateTime? = when (this) {
        NONE -> null
        IN_ONE_HOUR -> now.plusHours(1)
        TONIGHT -> now.withHour(20).withMinute(0).let { if (it <= now) it.plusDays(1) else it }
        TOMORROW -> now.plusDays(1).withHour(9).withMinute(0)
    }
}

class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val reminders: ReminderGateway,
    private val uiMessages: UiMessageBus,
) : ViewModel() {

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state

    init {
        viewModelScope.launch {
            taskRepository.observeAll().collect { list ->
                _state.value = _state.value.copy(
                    open = list.filter { !it.done },
                    done = list.filter { it.done },
                )
            }
        }
    }

    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    fun setReminderChoice(choice: ReminderChoice) {
        _state.value = _state.value.copy(draftReminder = choice)
    }

    fun addTask() {
        if (_state.value.adding) return
        val title = _state.value.draft.trim()
        if (title.isEmpty()) return
        val remindAt = _state.value.draftReminder.resolve(LocalDateTime.now())
        _state.value = _state.value.copy(adding = true)
        viewModelScope.launch {
            try {
                val remindMs = remindAt?.toEpochMs()
                val id = taskRepository.add(title, remindMs)
                if (remindMs != null) {
                    reminders.scheduleTask(id, title, remindMs)
                    if (!reminders.notificationsEnabled()) {
                        uiMessages.send(UiMessage.NotificationsOff)
                    }
                }
                _state.value = _state.value.copy(
                    draft = "",
                    draftReminder = ReminderChoice.NONE,
                    adding = false,
                )
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(adding = false)
                throw ce
            } catch (t: Throwable) {
                uiMessages.send(UiMessage.SaveFailed)
                _state.value = _state.value.copy(adding = false)
            }
        }
    }

    fun toggleDone(task: TaskEntity) {
        viewModelScope.launch {
            val nowDone = !task.done
            taskRepository.setDone(task, nowDone)
            if (nowDone) {
                reminders.cancelTask(task.id)
                reminders.cancelNotification(task.id)
            } else {
                task.remindAt?.let {
                    if (it > System.currentTimeMillis()) {
                        reminders.scheduleTask(task.id, task.title, it)
                    }
                }
            }
        }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch {
            reminders.cancelTask(task.id)
            reminders.cancelNotification(task.id)
            taskRepository.delete(task.id)
        }
    }
}
