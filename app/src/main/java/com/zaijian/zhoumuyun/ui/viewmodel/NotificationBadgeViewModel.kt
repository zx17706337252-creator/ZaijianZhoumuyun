package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// ─────────────────────────────────────────────────────────────
//  NotificationBadgeViewModel — MansionHeader 铃铛角标专用
//  通知中心设计方案 第四节。
//
//  与 NotificationViewModel 分开的原因：MansionHeader 每次公馆页
//  显示都会挂载，角标只需要一个 Int，不值得为此触发完整的
//  BriefingRepository.generateBriefing()（涉及关系/怀孕/任务/项目/
//  竞赛评分多表聚合）。这里走 BriefingRepository.observeAttentionItems()
//  的窄路线实时订阅——只聚合角标真正需要的 message/pregnancy/milestone/
//  关系矩阵四路子流，关系恶化、怀孕状态、消息时间、跨角色 Tension 任一
//  变化都会自动重算，不再是"挂载时查一次 + 短暂共享结果"。
//  SharingStarted.WhileSubscribed(5_000) 仍然保留，用于在多次快速重组
//  之间共享同一条订阅，避免重复启动上游 Flow。
// ─────────────────────────────────────────────────────────────

class NotificationBadgeViewModel(application: Application) : AndroidViewModel(application) {

    private val notificationRepo = AppContainer.instance.notificationRepo
    private val briefingRepo     = AppContainer.instance.briefingRepo

    // 批次4 4-2修复（完全版）：原 attentionItemKeys 在字段初始化时一次性算好 since
    // （System.currentTimeMillis() - 30天），整个 ViewModel 生命周期不再重算，
    // since 越拖越旧，角标会比通知中心多包含一段旧里程碑。
    // 上一版用 flow { emit } 只在"上游从0订阅者重新变为有订阅者"时重算 since，
    // 配合 collectAsStateWithLifecycle + WhileSubscribed(5_000) 能覆盖切后台场景，
    // 但如果订阅从未连续中断超过5秒（App一直在前台使用），since 仍会长期固化。
    // 改为 sinceTicker：每小时自动重新 emit 一次当前 since，用 flatMapLatest 重新
    // 订阅 observeAttentionItems（旧订阅被取消，新订阅立刻用最新 since 重新查询），
    // 不依赖任何前后台切换或订阅者归零，纯靠时间驱动，误差控制在 1 小时内，
    // 与 NotificationViewModel.load() 的口径对齐。
    private val sinceTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() - 30 * 86_400_000L)
            delay(60 * 60 * 1000L)  // 每小时重算一次 since
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val attentionItemKeys: Flow<List<String>> =
        sinceTicker.flatMapLatest { since ->
            briefingRepo.observeAttentionItems(since)
                .map { items -> items.map { notificationRepo.buildItemKey(it) } }
        }

    /**
     * 未读角标数 = attentionItemKeys 里不在已读表的条数。
     * 好消息区块不参与计数（设计稿第四节明确：好消息不该制造紧迫感）。
     */
    val unreadCount: StateFlow<Int> =
        combine(attentionItemKeys, notificationRepo.observeReadKeys()) { attentionKeys, readKeys ->
            attentionKeys.count { it !in readKeys }
        }.stateIn(
            scope         = viewModelScope,
            started       = SharingStarted.WhileSubscribed(5_000),
            initialValue  = 0,
        )
}