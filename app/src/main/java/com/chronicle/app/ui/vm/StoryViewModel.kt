package com.chronicle.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.domain.Story
import com.chronicle.app.domain.StoryChapter
import com.chronicle.app.domain.StoryComposer
import com.chronicle.app.domain.toDomain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

// ————— Story —————

data class StoryUiState(
    val story: Story? = null,
    /**
     * The ISO-week Monday of each chapter — parallel to `story.chapters`
     * (same grouping [StoryComposer] uses). Unlike `chapter.number`, a week
     * start survives renumbering when an earlier week is deleted, so it's the
     * stable id the reader route is keyed on.
     */
    val chapterWeeks: List<LocalDate> = emptyList(),
) {
    /** The chapter whose week starts on [weekStart] — null once it's gone. */
    fun chapterAt(weekStart: LocalDate): StoryChapter? =
        story?.chapters?.getOrNull(chapterWeeks.indexOf(weekStart))
}

@OptIn(ExperimentalCoroutinesApi::class) // mapLatest
class StoryViewModel(
    private val journalRepository: JournalRepository,
    /** Where entry→story composition runs — compose is O(entries) per edit. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(StoryUiState())
    val state: StateFlow<StoryUiState> = _state

    init {
        viewModelScope.launch {
            journalRepository.observeAll()
                .mapLatest { list ->
                    val entries = list.map { it.toDomain() }
                    StoryComposer.compose(entries, LocalDate.now()) to
                        entries.map { it.day.with(DayOfWeek.MONDAY) }
                            .distinct()
                            .sorted()
                }
                .flowOn(computeDispatcher)
                .collect { (story, weeks) ->
                    _state.value = _state.value.copy(
                        story = story,
                        chapterWeeks = weeks,
                    )
                }
        }
    }
}
