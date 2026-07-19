package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 进化方案实体（Phase 22）
 *
 * 每个角色拥有独立的进化方案（Agent 通过 plan_save 工具写入）。
 * 方案内容注入 AgentPlan Layer（System Prompt 第5层，在 World 之前）。
 *
 * 同一角色只保留一条有效方案（isActive=true），写入时旧方案自动归档。
 */
@Entity(
    tableName = "agent_plans",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "isActive"]),
        Index(value = ["createdAt"]),
    ]
)
data class AgentPlanEntity(
    @PrimaryKey val id: String,

    /** 所属角色 ID（1-9） */
    val characterId: Int,

    /** 方案标题（Agent 自定义，≤50字） */
    val title: String,

    /**
     * 方案正文（Markdown 格式，Agent 描述自己的进化方向、优先行动、技能目标等）。
     * 注入 AgentPlan Layer 时截取前 500 tokens。
     */
    val content: String,

    /** 是否为当前有效方案（同一角色只有一条 isActive=true） */
    val isActive: Boolean = true,

    val createdAt: Long,
    val updatedAt: Long,
)
