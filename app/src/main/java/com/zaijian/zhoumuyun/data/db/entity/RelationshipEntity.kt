package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  Relationship Engine — 数据层（设计方案 §8）
//
//  六维非对称模型：fromId → toId 与 toId → fromId 是独立记录。
//  fromId 可为 "user" 或角色 ID 字符串，toId 始终为角色 ID。
//
//  Phase 14 新增字段：
//  - jealousy（嫉妒度）：Bot↔Bot 专属，影响圆桌发言意愿
//  - tension（紧张度）：Bot↔Bot 专属，影响圆桌反驳概率
//  - isInterCharacter：true=角色间关系，false=用户↔角色关系
// ─────────────────────────────────────────────────────────────

enum class RelationshipStage {
    STRANGER,    // 陌生人（初始）
    FAMILIAR,    // 熟悉
    TRUSTED,     // 信任
    IMPORTANT,   // 重要
    CORE,        // 核心（最高阶段）
}

@Entity(
    tableName = "relationship_states",
    indices = [
        Index("fromId"),
        Index("toId"),
        Index(value = ["fromId", "toId"], unique = true),
        Index("isInterCharacter"),
    ],
)
data class RelationshipEntity(
    @PrimaryKey val id: String,
    val fromId: String,        // "user" 或角色 ID（字符串）
    val toId: String,          // 角色 ID（字符串）
    val trust: Int      = 50,  // 0-100，影响主动分享和建议采纳率
    val respect: Int    = 50,  // 0-100，影响意见接受度
    val affection: Int  = 50,  // 0-100，影响互动温度
    val curiosity: Int  = 50,  // 0-100，影响主动提问频率
    val dependence: Int = 30,  // 0-100，影响主动关注频率
    val conflict: Int   = 10,  // 0-100，影响质疑和反对概率
    // ── Phase 14 新增：Bot↔Bot 专属字段 ────────────────────
    /** 嫉妒度（0-100）：上轮另一 Bot 发言多 → 发言意愿+；仅角色间有意义 */
    val jealousy: Int   = 0,
    /** 紧张度（0-100）：观点持续对立时积累，影响圆桌反驳概率 */
    val tension: Int    = 0,
    /** 是否为角色间关系（true=角色↔角色，false=用户↔角色） */
    val isInterCharacter: Boolean = false,
    /**
     * 压抑感（0-100）：0=完全压抑（角色心防极高），100=完全释放。
     * 用户↔角色 专属；影响 Identity Layer 中私下说话方式的激活条件。
     * 默认 50（中立）；随对话关键词自动微调，速率远低于 affection。
     */
    val suppression: Int = 50,
    val stage: String   = RelationshipStage.STRANGER.name,
    val sourceEventId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
