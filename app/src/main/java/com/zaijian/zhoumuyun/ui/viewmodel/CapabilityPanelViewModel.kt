package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.repository.CharacterCapabilitySnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════
//  CapabilityPanelViewModel（Window D-4 · 能力面板挂载）
//
//  职责：
//  - 从 CapabilityPanelRepository 获取角色能力快照（工具列表 + 最近活动 + 进行中工作流）
//  - 提供 refresh() 供下拉刷新或手动刷新
//
//  范式对齐 IdentityViewModel.kt：
//  AndroidViewModel + AppContainer.instance.xxxRepo + MutableStateFlow
//  因 Repository 是 suspend（非 Flow），采用 init() 驱动模式手动加载。
//
//  数据契约见 CapabilityPanelRepository.kt（Window B 定稿，§2.2.4）。
// ═══════════════════════════════════════════════════════════════

/** 能力面板 UI 状态 */
data class CapabilityPanelUiState(
    val isLoading: Boolean = true,
    val snapshot: CharacterCapabilitySnapshot? = null,
    val error: String? = null,
)

class CapabilityPanelViewModel(application: Application) : AndroidViewModel(application) {

    private val capabilityPanelRepo = AppContainer.instance.capabilityPanelRepo

    private val _uiState = MutableStateFlow(CapabilityPanelUiState())
    val uiState: StateFlow<CapabilityPanelUiState> = _uiState.asStateFlow()

    private var currentCharacterId: Int = -1
    // P1-23 修复：持有当前加载 Job，切换角色时先取消旧 Job，避免慢查询的旧结果覆盖新角色。
    private var loadJob: Job? = null

    /**
     * 加载角色能力快照。
     * P2-7-3：原先"重复传入相同 characterId 且已有快照则早退（幂等）"导致任务→技能→能力
     * 来回切（同 characterId）时展示陈旧快照，期间新产生的数据不出现。现去掉该早退，重进即
     * 重新拉取；并发重复加载由下方 P1-23 的 loadJob?.cancel() 兜底，不会让旧结果覆盖新角色。
     */
    fun load(characterId: Int) {
        currentCharacterId = characterId
        _uiState.update { it.copy(isLoading = true, error = null) }
        // P1-23 修复：先取消上一次的加载（getCharacterCapabilities 是挂起慢查询，若
        // load(A) 进行中再 load(B)，两个协程并发，后完成的 A 会覆盖当前 B 的快照）。
        // 与同库 TimelineViewModel/FamilyListViewModel 等显式 cancel 旧 Job 的做法对齐。
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val snapshot = capabilityPanelRepo.getCharacterCapabilities(characterId)
                _uiState.update {
                    it.copy(isLoading = false, snapshot = snapshot, error = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "加载失败")
                }
            }
        }
    }

    /** 强制刷新（忽略幂等检查）。 */
    fun refresh() {
        val cid = currentCharacterId
        if (cid < 0) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = capabilityPanelRepo.getCharacterCapabilities(cid)
                _uiState.update {
                    it.copy(isLoading = false, snapshot = snapshot, error = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "刷新失败")
                }
            }
        }
    }
}
