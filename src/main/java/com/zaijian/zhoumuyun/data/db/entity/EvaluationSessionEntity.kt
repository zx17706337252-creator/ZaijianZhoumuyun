package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 打分会话实体（Phase 24）
 *
 * 每次 AI 回复 ≥150 字且当前角色有激活目标时，自动创建一条 EvaluationSession。
 * 完整生命周期：
 *   PENDING → [Agent B 异步评审] → REVIEWED → [用户手动打分] → SCORED
 *
 * 综合分 = agentScore × 0.4 + userScore × 0.6（Phase 26 提炼阈值判定用）
 *
 * 版本历史：
 *   v9（Phase 24）：新增本表
 */
@Entity(
    tableName = "evaluation_sessions",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "goalId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
    ]
)
data class EvaluationSessionEntity(
    @PrimaryKey val id: String,

    /** 所属角色 ID */
    val characterId: Int,

    /** 关联的激活学习目标 ID（触发时取第一条激活目标） */
    val goalId: String,

    /** 触发本次评审的 AI 回复消息 ID */
    val triggerMessageId: String,

    /**
     * 评审会话状态（文本枚举）：
     *   PENDING   — 已创建，等待 Agent B 评审
     *   REVIEWED  — Agent B 已完成评审，等待用户打分
     *   SCORED    — 用户已打分，综合分已计算
     *   SKIPPED   — 用户跳过打分（不纳入提炼统计）
     */
    val status: String = EvaluationStatus.PENDING.name,

    // ── Agent B 评审结果 ──────────────────────────────────────

    /**
     * Agent B 的评审维度得分（JSON 字符串，冗余存储便于后续分析）。
     * 格式：{"relevance": 3.5, "depth": 4.0, "style": 3.0, "overall": 3.5}
     * null = 评审尚未完成
     */
    val agentScoreJson: String? = null,

    /** Agent B 的综合评分 1.0–5.0，null = 尚未评审 */
    val agentScore: Float? = null,

    /** Agent B 的评语摘要（≤100字），null = 尚未评审 */
    val agentComment: String? = null,

    // ── 用户打分 ──────────────────────────────────────────────

    /** 用户打分 1–5（星级），null = 尚未打分 */
    val userScore: Int? = null,

    /** 用户补充备注（可选），null = 用户未填写 */
    val userNote: String? = null,

    // ── 综合分 ────────────────────────────────────────────────

    /**
     * 综合分 = agentScore × 0.4 + userScore × 0.6。
     * 由 SCORED 状态写入，Phase 26 按此值决策是否触发提炼。
     * null = 尚未计算
     */
    val compositeScore: Float? = null,

    // ── Agent B 汇报消息 ──────────────────────────────────────

    /**
     * Agent A 向用户展示的评审汇报文本（Agent B 结果格式化后存储）。
     * ChatViewModel 读取后插入消息流，驱动用户打分卡片。
     * null = 汇报尚未生成
     */
    val reportText: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 综合分计算（供外部工具函数调用） */
    fun calcCompositeScore(): Float? {
        val a = agentScore ?: return null
        val u = userScore?.toFloat() ?: return null
        return a * 0.4f + u * 0.6f
    }
}

// ─────────────────────────────────────────────────────────────
//  EvaluationStatus 枚举
// ─────────────────────────────────────────────────────────────

enum class EvaluationStatus {
    PENDING,
    REVIEWED,
    SCORED,
    SKIPPED,
}
