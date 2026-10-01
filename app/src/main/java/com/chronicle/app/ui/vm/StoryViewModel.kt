package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.domain.Story
import com.chronicle.app.domain.StoryComposer
import com.chronicle.app.domain.toDomain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

// ————— Story —————

data class StoryUiState(
    val story: Story? = null,
    val selectedChapter: Int? = null,
)

class StoryViewModel(private val journalRepository: JournalRepository) : ViewModel() {

    private val _state = MutableStateFlow(StoryUiState())
    val state: StateFlow<StoryUiState> = _state

    init {
        viewModelScope.launch {
            journalRepository.observeAll().collect { list ->
                val story = StoryComposer.compose(
                    list.map { it.toDomain() },
                    LocalDate.now(),
                )
                _state.value = _state.value.copy(story = story)
            }
        }
    }

    fun selectChapter(number: Int?) {
        _state.value = _state.value.copy(selectedChapter = number)
    }
}
