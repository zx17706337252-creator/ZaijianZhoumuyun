package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TimelineUiState(
    val events: List<WorldEventEntity> = emptyList(),
    val isLoading: Boolean = true,
)

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val eventRepo = com.zaijian.zhoumuyun.data.repository.EventRepository(db.worldEventDao())

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    fun load(actorId: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val events = withContext(Dispatchers.IO) {
                if (actorId != null) eventRepo.queryByActor(actorId, 100)
                else eventRepo.queryLatest(100)
            }
            _uiState.update { it.copy(events = events, isLoading = false) }
        }
    }
}
