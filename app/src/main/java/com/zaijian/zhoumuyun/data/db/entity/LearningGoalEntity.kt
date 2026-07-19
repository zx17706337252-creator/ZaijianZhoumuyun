package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 学习目标实体（Phase 22 引入，Phase 23 深化）
 *
 * Agent 通过 goal_update 工具推进学习目标进度。
 * 目标与 AgentPlan 关联（agentPlanId），支持按角色独立管理。
 *
 * 完整 UI 和打分机制在 Phase 23-24 中实现；
 * Phase 22 仅需：表结构 + goal_update 工具 + DAO 基础查询。
 */
@Entity(
    tableName = "learning_goals",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "isActive"]),
        Index(value = ["agentPlanId"]),
        Index(value = ["createdAt"]),
    ]
)
data class LearningGoalEntity(
    @PrimaryKey val id: String,

    /** 所属角色 ID（1-9，每个角色的目标完全独立） */
    val characterId: Int,

    /** 关联的 AgentPlan ID（可空，Phase 22 引入，Phase 23 UI 完善） */
    val agentPlanId: String? = null,

    /** 目标标题（≤50字） */
    val title: String,

    /** 目标描述（用户设定目标的意图，Agent 在 goal_update 时参考） */
    val description: String = "",

    /**
     * 目标进度 0.0–1.0。
     * goal_update 工具每次调用可增加进度（0.0–1.0 范围，不超过 1.0）。
     */
    val progress: Float = 0f,

    /** 是否为激活状态（激活目标才注入 Rule Layer 和 AgentPlan Layer） */
    val isActive: Boolean = true,

    /**
     * 目标状态（文本枚举）：
     *   IN_PROGRESS / COMPLETED / PAUSED / ABANDONED
     */
    val status: String = "IN_PROGRESS",

    /**
     * 指定的评审角色 ID（Phase 24 Agent B 打分用）。
     * Phase 22 写入时可为 null，Phase 24 UI 完善后填充。
     */
    val designatedReviewerId: Int? = null,

    /**
     * 最近一次 goal_update 的备注（Agent 写入，记录本次进步点）。
     */
    val lastUpdateNote: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
)

// ─────────────────────────────────────────────────────────────
//  LearningGoalStatus 枚举（与 status 字段对应）
// ─────────────────────────────────────────────────────────────

enum class LearningGoalStatus {
    IN_PROGRESS,
    COMPLETED,
    PAUSED,
    ABANDONED,
}
