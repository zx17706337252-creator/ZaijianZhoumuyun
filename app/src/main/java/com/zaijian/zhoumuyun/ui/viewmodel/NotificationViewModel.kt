package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.BriefingAttentionItem
import com.zaijian.zhoumuyun.data.model.BriefingData
import com.zaijian.zhoumuyun.data.repository.GoodNewsItem
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  NotificationViewModel — 通知中心页面
//  通知中心设计方案 全文。结构参照 BriefingViewModel
//  （AndroidViewModel + MutableStateFlow<XxxUiState> + asStateFlow()）。
//
//  与 BriefingViewModel 的关键差异：Briefing 是"一次性开场页"，
//  since 取自上次打开时间；通知中心是"常驻可反复查看"入口，每次
//  since 固定回溯一个较长窗口（见 LOOKBACK_WINDOW_MS），不消费/
//  推进 BriefingDataStore 的 lastOpenAt——两边的时间游标互不干扰，
//  通知中心反复打开不会影响下次开场简报的统计区间。
// ─────────────────────────────────────────────────────────────

/** 通知中心统计窗口固定 30 天回溯，不像 Briefing 依赖"上次打开时间"游标。 */
private const val LOOKBACK_WINDOW_MS = 30 * 86_400_000L

data class NotificationUiState(
    val isLoading: Boolean = true,
    val attentionItems: List<BriefingAttentionItem> = emptyList(),
    val goodNewsItems: List<GoodNewsItem> = emptyList(),
    /**
     * 技术债清理（见 CHANGES_S9_window01_notification_center.md 技术债第 2 条）：
     * 原先这里是 Set<String>，UI 层（NotificationAttentionSection.buildDisplayKey()）
     * 必须自己重新拼一遍 key 才能跟这个 Set 比对，两处拼接规则要手动保持同步，
     * 一旦漏改一处就会导致已读状态永远对不上。
     *
     * 现在直接暴露 Set<BriefingAttentionItem> 本身（已读的条目集合，而非
     * 已读的 key 集合）。UI 层判断"这条是否已读"只需要 `item in readItems`，
     * 不再需要知道 itemKey 是怎么拼的——key 拼接规则从此只存在于
     * NotificationRepository.buildItemKey() 一处，UI 层不再持有第二份实现。
     */
    val readItems: Set<BriefingAttentionItem> = emptySet(),
    val daughterNameMap: Map<String, String> = emptyMap(),
    val error: String? = null,
)

class NotificationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private val notificationRepo = AppContainer.instance.notificationRepo
    private val briefingRepo     = AppContainer.instance.briefingRepo

    init {
        viewModelScope.launch { load() }
    }

    fun refresh() {
        viewModelScope.launch {
            // 用 update {} 而不是"读 value → copy → 写回"：理由同
            // markItemRead() 的注释——避免跟正在进行中的 markItemRead()
            // 并发写 _uiState 时互相覆盖（2026-07-18 修复）。
            _uiState.update { it.copy(isLoading = true, error = null) }
            load()
        }
    }

    private suspend fun load() {
        try {
            val since = System.currentTimeMillis() - LOOKBACK_WINDOW_MS
            val data: BriefingData = briefingRepo.generateBriefing(since)
            val goodNews = notificationRepo.buildGoodNewsItems(data)
            val readKeys = notificationRepo.observeReadKeys()
            // Flow.first() 取一次快照即可，本页用 markItemRead() 之后手动
            // 更新内存态 uiState，不需要长期订阅 Flow（避免每次已读表变化
            // 都触发整页重新聚合 BriefingData，那是不必要的重复查询）。
            val readKeysSnapshot = readKeys.first()
            // itemKey 拼接规则只在 NotificationRepository.buildItemKey() 存在
            // 这一处，这里用它把"已读 key 集合"翻译成"已读条目集合"，UI 层
            // 从此不需要知道 key 是怎么拼的（技术债第 2 条清理）。
            val readItemsSnapshot = data.attentionItems.filter {
                notificationRepo.buildItemKey(it) in readKeysSnapshot
            }.toSet()

            _uiState.value = NotificationUiState(
                isLoading       = false,
                attentionItems  = data.attentionItems,
                goodNewsItems   = goodNews,
                readItems       = readItemsSnapshot,
                daughterNameMap = loadDaughterNameMap(data.attentionItems),
            )

            // 孤儿已读数据清理：用本次"需要关注"区块产出的全部 itemKey 做基准。
            notificationRepo.pruneStaleReadState(
                data.attentionItems.map { notificationRepo.buildItemKey(it) }
            )
        } catch (e: Exception) {
            ZLog.e("NotificationViewModel", "通知中心数据加载失败", e)
            // 同上，用 update {} 避免跟并发的 markItemRead() 互相覆盖。
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "加载失败：${e.message?.take(100) ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * 用户点开某条 → 标记已读 → 立即更新内存态（角标数据源见 observeUnreadCountFlow）。
     *
     * 用 MutableStateFlow.update {} 而不是"读 _uiState.value → copy → 写回"，
     * 是因为后者在两次 markItemRead() 几乎同时触发（用户快速点开两条不同
     * 通知）时，两个协程可能都读到同一份旧值，后写的会覆盖先写的，其中
     * 一条的已读状态在内存里丢失（DB 里两条都真实写进去了，只是内存态
     * 显示不同步，要等下次 load() 才会纠正）。update {} 内部对同一个
     * MutableStateFlow 的并发调用是原子的，不会互相覆盖（深度检查发现，
     * 2026-07-18 修复）。
     */
    fun markItemRead(item: BriefingAttentionItem) {
        viewModelScope.launch {
            notificationRepo.markRead(item)
            _uiState.update { it.copy(readItems = it.readItems + item) }
        }
    }

    // ── 女儿角色名预加载 ─────────────────────────────────────
    // 技术债清理（见 CHANGES_S9_window01_notification_center.md 技术债第 1 条）：
    // 原先与 BriefingViewModel.loadDaughterNameMap() 是两份完全重复的实现，
    // 现收敛到 DaughterCharacterRepository.resolveDaughterNames() 共享方法，
    // 这里只负责从 attentionItems 里收集候选 ID。
    private suspend fun loadDaughterNameMap(
        attentionItems: List<BriefingAttentionItem>,
    ): Map<String, String> {
        val candidateIds = attentionItems.flatMap { item ->
            when (item) {
                is BriefingAttentionItem.Tension -> listOf(item.fromId, item.toId)
                is BriefingAttentionItem.RelationWorsened -> listOf(item.fromId, item.toId)
                else -> emptyList()
            }
        }
        return AppContainer.instance.daughterCharacterRepo
            .resolveDaughterNames(candidateIds, logTag = "NotificationViewModel")
    }
}