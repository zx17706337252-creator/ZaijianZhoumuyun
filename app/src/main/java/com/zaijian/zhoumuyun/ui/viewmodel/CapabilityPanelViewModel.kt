package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.repository.CharacterCapabilitySnapshot
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

    /**
     * 加载角色能力快照。
     * 重复传入相同 characterId 不会重新加载（幂等），需手动调 [refresh] 强制刷新。
     */
    fun load(characterId: Int) {
        if (currentCharacterId == characterId && _uiState.value.snapshot != null) return
        currentCharacterId = characterId
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = capabilityPanelRepo.getCharacterCapabilities(characterId)
                _uiState.update {
                    it.copy(isLoading = false, snapshot = snapshot, error = null)
                }
            } catch (e: Exception) {
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "刷新失败")
                }
            }
        }
    }
}
