package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.datastore.BriefingDataStore
import com.zaijian.zhoumuyun.data.model.BriefingData
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  BriefingViewModel — 离线简报开场页
//  整合方案 v2.1 4.7 节。结构参照 PresenceViewModel
//  （AndroidViewModel + MutableStateFlow<XxxUiState> + asStateFlow()）。
//
//  时间起点逻辑：
//    - 首次安装（BriefingDataStore 里没有记录）→ 起点回退为 7 天前。
//    - 非首次 → 起点是「上一次简报生成完成时」记下的真实时间戳，
//      不是固定 7 天，如实反映距上次打开过去了多久。
//    - 生成完本次简报后，立即把当前时间写回 DataStore，作为下一次
//      简报的起点，保证统计区间首尾相接、不漏不重。
// ─────────────────────────────────────────────────────────────

private const val FALLBACK_WINDOW_MS = 7 * 86_400_000L // 首次安装兜底：7 天

data class BriefingUiState(
    val isLoading: Boolean = true,
    val data: BriefingData? = null,
    val error: String? = null,
    /**
     * S8-窗口01 修复：女儿角色 ID → 名称映射，供 BriefingAttentionSection 展示
     * Tension/RelationWorsened 涉及女儿角色（characterId >= 1000）时的名字。
     * 原先由 BriefingAttentionSection 在 Composable 内自行 LaunchedEffect +
     * AppContainer.instance.daughterCharacterRepo 查询，是 UI 层绕过 ViewModel
     * 直接访问 Repository 的分层违规；现由 ViewModel 在 loadBriefing() 时一并
     * 算好，Section 变为纯展示组件，不再持有任何数据访问逻辑。
     */
    val daughterNameMap: Map<String, String> = emptyMap(),
)

class BriefingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BriefingUiState())
    val uiState: StateFlow<BriefingUiState> = _uiState.asStateFlow()

    private val store = BriefingDataStore(application)

    init {
        viewModelScope.launch {
            loadBriefing()
        }
    }

    /**
     * W14 修复：提供 refresh() 方法供 UI 层调用（下拉刷新或重试按钮）。
     * 与 init 中的 loadBriefing() 共享同一份逻辑，避免重复代码。
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            loadBriefing()
        }
    }

    private suspend fun loadBriefing() {
        try {
            val lastOpenAt = store.lastOpenAtFlow.first()
            val since = lastOpenAt ?: (System.currentTimeMillis() - FALLBACK_WINDOW_MS)
            val data = AppContainer.instance.briefingRepo.generateBriefing(since)
            _uiState.value = BriefingUiState(
                isLoading       = false,
                data            = data,
                daughterNameMap = loadDaughterNameMap(data.attentionItems),
            )
            store.setLastOpenAt(System.currentTimeMillis())
        } catch (e: Exception) {
            ZLog.e("BriefingViewModel", "generateBriefing 失败", e)
            _uiState.value = BriefingUiState(
                isLoading = false,
                error = "简报生成失败：${e.message?.take(100) ?: "未知错误"}"
            )
            // 审查报告问题17修复：生成失败时也必须推进时间窗口，否则 lastOpenAt
            // 不变，下次打开会用同一个 since 重新尝试生成同一时间段的简报——
            // 如果失败是持久性的（数据损坏等），用户会永远卡在同一个错误上，
            // 无法"跳过"这个时间段。失败也是"已经尝试过"，时间窗口应照常推进。
            store.setLastOpenAt(System.currentTimeMillis())
        }
    }

    /**
     * S8-窗口01 修复：从原 BriefingAttentionSection.kt 搬迁的女儿角色名预加载逻辑
     * （P1-18 修复：避免 Tension/RelationWorsened 中涉及女儿角色时显示裸 ID）。
     *
     * 技术债清理（见 CHANGES_S9_window01_notification_center.md 技术债第 1 条）：
     * 原先与 NotificationViewModel.loadDaughterNameMap() 是两份完全重复的实现，
     * 现收敛到 DaughterCharacterRepository.resolveDaughterNames() 共享方法，
     * 这里只负责从 attentionItems 里收集候选 ID。
     */
    private suspend fun loadDaughterNameMap(
        attentionItems: List<com.zaijian.zhoumuyun.data.model.BriefingAttentionItem>,
    ): Map<String, String> {
        val candidateIds = attentionItems.flatMap { item ->
            when (item) {
                is com.zaijian.zhoumuyun.data.model.BriefingAttentionItem.Tension ->
                    listOf(item.fromId, item.toId)
                is com.zaijian.zhoumuyun.data.model.BriefingAttentionItem.RelationWorsened ->
                    listOf(item.fromId, item.toId)
                else -> emptyList()
            }
        }
        return AppContainer.instance.daughterCharacterRepo
            .resolveDaughterNames(candidateIds, logTag = "BriefingViewModel")
    }
}
