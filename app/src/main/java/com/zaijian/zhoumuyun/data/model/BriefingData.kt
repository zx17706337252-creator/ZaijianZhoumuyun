package com.zaijian.zhoumuyun.data.model

import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity

// ─────────────────────────────────────────────────────────────
//  BriefingData — 离线简报（整合方案 v2.1 第四节）
//
//  角色范围 = 9 位母亲（isUnlocked）+ 全部已完成注册的女儿（1000+）。
//  只读聚合结构，由 BriefingRepository.generateBriefing() 产出，
//  不落库、不持久化，每次打开 App 现算一次。
// ─────────────────────────────────────────────────────────────

data class BriefingData(
    val periodStart: Long,
    val periodEnd: Long,
    val characters: List<BriefingCharacterEntry>,
    val attentionItems: List<BriefingAttentionItem>,
    val affectionRanking: List<BriefingCharacterEntry>,
    /**
     * W14 修复：部分角色聚合失败时的错误信息列表。
     * BriefingRepository.generateBriefing() 内部按角色独立 try-catch，
     * 单个角色数据损坏不影响其他角色，失败的角色在此记录错误信息。
     * 空列表 = 所有角色聚合成功。
     */
    val partialErrors: List<String> = emptyList(),
)

data class BriefingCharacterEntry(
    val character: CharacterConfig,
    val relation: RelationshipEntity?,
    val lastMessageAt: Long?,
    val daysSinceContact: Long?,
    val isPregnant: Boolean,
    val cyclePhase: CyclePhase,
    val completedTaskCount: Int,
    val projectNames: List<String> = emptyList(),
    val competitionScore: Float? = null,
    /** 本次统计周期内该角色是否有 REPAIRED 方向的关系转折点，供 WorldCard 的 isMilestone 用。 */
    val hasRecentGoodMilestone: Boolean = false,
)

sealed class BriefingAttentionItem {
    data class NoContact(val character: CharacterConfig, val days: Long) : BriefingAttentionItem()
    /** 该角色自生成/注册以来从未有过消息记录——与 NoContact 是不同语义，不能塞进 days 字段填数字表达。 */
    data class NeverContacted(val character: CharacterConfig) : BriefingAttentionItem()
    data class Pregnancy(val character: CharacterConfig) : BriefingAttentionItem()
    data class Tension(val fromId: String, val toId: String, val tension: Int) : BriefingAttentionItem()
    // milestoneId：对应 RelationshipMilestoneEntity.id。同一对角色在同一
    // 统计窗口内可能发生多次独立的关系恶化事件（milestone 表按事件追加，
    // 不按角色对去重），fromId+toId 不足以唯一标识"这一条"恶化条目——
    // 通知中心需要按 itemKey 做已读状态追踪，缺这个字段会导致同一对角色
    // 的两次不同恶化事件共享同一个 key，标记其一为已读时把另一条也带
    // 已读了（深度检查发现，2026-07-18 修复）。BriefingAttentionSection.kt
    // 展示层不使用这个字段，不影响简报页原有行为。
    data class RelationWorsened(
        val fromId: String,
        val toId: String,
        val description: String,
        val milestoneId: String,
    ) : BriefingAttentionItem()
}
