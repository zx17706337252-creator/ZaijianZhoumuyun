package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.ContentBlock
import com.zaijian.zhoumuyun.ui.component.ContentBlockAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════
//  AgentActivityViewModel（窗口7贯通 · 心迹面板）
//
//  职责：
//  - 调用 AgentActivityRepository.observeTimeline() 获取角色心迹 Flow
//  - 经 ContentBlockAdapter.fromTimelineItems() 转换为 List<ContentBlock>
//  - 暴露为 StateFlow 供 UI 响应式消费
//
//  贯通链路：
//    observeTimeline() → Flow<List<AgentActivityTimelineItem>>
//      → ContentBlockAdapter.fromTimelineItems() → List<ContentBlock>
//        → ContentBlockRenderer 渲染（AgentActivityTimelinePanel）
//
//  范式对齐 CapabilityPanelViewModel.kt：
//  AndroidViewModel + AppContainer.instance.xxxRepo
//  区别：observeTimeline() 返回 Flow（非 suspend），用 collect 驱动而非手动 load。
// ═══════════════════════════════════════════════════════════════

/** 心迹面板 UI 状态 */
data class AgentActivityUiState(
    val isLoading: Boolean = true,
    val blocks: List<ContentBlock> = emptyList(),
    val error: String? = null,
)

class AgentActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val agentActivityRepo = AppContainer.instance.agentActivityRepo

    private val _uiState = MutableStateFlow(AgentActivityUiState())
    val uiState: StateFlow<AgentActivityUiState> = _uiState.asStateFlow()

    private var currentCharacterId: Int = -1
    private var collectJob: kotlinx.coroutines.Job? = null

    /**
     * 初始化心迹订阅。重复传入相同 characterId 不会重新订阅（幂等）。
     *
     * 调用 [AgentActivityRepository.observeTimeline] 获取 Flow，
     * 经 [ContentBlockAdapter.fromTimelineItems] 转换后更新 UI 状态。
     */
    fun init(characterId: Int) {
        if (currentCharacterId == characterId && collectJob?.isActive == true) return
        currentCharacterId = characterId

        collectJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }

        collectJob = viewModelScope.launch {
            try {
                agentActivityRepo.observeTimeline(characterId).collect { timelineItems ->
                    val blocks = ContentBlockAdapter.fromTimelineItems(timelineItems)
                    _uiState.update {
                        it.copy(isLoading = false, blocks = blocks, error = null)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "加载心迹失败")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectJob?.cancel()
    }
}
