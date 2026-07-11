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
    data class RelationWorsened(val fromId: String, val toId: String, val description: String) : BriefingAttentionItem()
}
