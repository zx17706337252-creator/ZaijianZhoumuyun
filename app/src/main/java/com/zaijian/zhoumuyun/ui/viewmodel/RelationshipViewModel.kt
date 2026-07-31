package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.data.repository.RelQueryResult
import kotlinx.coroutines.flow.Flow

// ═══════════════════════════════════════════════════════════════
//  RelationshipViewModel（E0 分层收口 · 关系面板）
//
//  职责：
//  - 把 CharacterDetailScreen（Hero 卡片 BondRibbon）与 RelationshipPanel
//    原先对 AppContainer.instance.relationshipReadRepo 的直接持有，收敛到
//    ViewModel 层。UI 侧只调用本 ViewModel 的只读方法 / 订阅返回的 Flow，
//    不再直接触碰 Repository（E0 coupling_scan 违规点 #3、#5 的修复落地）。
//
//  范式对齐 AgentActivityViewModel.kt：
//  AndroidViewModel + AppContainer.instance.xxxRepo；Repo 侧 Flow 已内置
//  .catch{} 兜底、suspend 侧已内置 try-catch 兜底，本 ViewModel 仅做透传，
//  不重复异常处理。
// ═══════════════════════════════════════════════════════════════

class RelationshipViewModel(application: Application) : AndroidViewModel(application) {

    private val relationshipReadRepo = AppContainer.instance.relationshipReadRepo

    /**
     * 观察 fromId（通常是 "user"）到 toId 这一条关系记录的实时变化。
     * 对应原 CharacterDetailScreen.heroRelFlow / RelationshipPanel.relFlow。
     */
    fun observeRelationTo(fromId: String, toId: String): Flow<RelationshipEntity?> =
        relationshipReadRepo.observeRelationTo(fromId, toId)

    /**
     * 取 actorId→targetId 之间最近的关系变化事件（RELATIONSHIP_CHANGED 类型）。
     * C7#21 修复：返回 [RelQueryResult]，"无数据"和"查询失败"两种情形不再被
     * Repo 强行合并成同一个空列表，交给 UI 层区分展示。
     */
    suspend fun getRecentRelationshipEvents(
        actorId: String,
        targetId: String,
        queryLimit: Int = 8,
    ): RelQueryResult<List<WorldEventEntity>> =
        relationshipReadRepo.getRecentRelationshipEvents(actorId, targetId, queryLimit)

    /**
     * 取 fromId→toId 最近的关系转折点（Milestone）。
     * C7#21 修复：同上，返回 [RelQueryResult]。
     */
    suspend fun getRecentMilestones(
        fromId: String,
        toId: String,
        limit: Int = 10,
    ): RelQueryResult<List<RelationshipMilestoneEntity>> =
        relationshipReadRepo.getRecentMilestones(fromId, toId, limit)
}
