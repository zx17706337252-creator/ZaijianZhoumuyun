package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  Character Goal System — 数据层（设计方案 §9）
//
//  Goal 是连接「角色是谁」与「角色在做什么」的桥梁。
//  没有 Goal，Presence 和 World Simulation 都缺乏行为来源。
// ─────────────────────────────────────────────────────────────

enum class GoalHorizon {
    SHORT_TERM,   // 短期（数天内）
    MID_TERM,     // 中期（数周内）
    LONG_TERM,    // 长期（持续目标）
}

@Entity(
    tableName = "character_goals",
    indices = [
        Index("characterId"),
        Index("isActive"),
        Index("relatedProjectId"),
    ],
)
data class CharacterGoalEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val title: String,
    val description: String      = "",
    val priority: Int            = 3,            // 1-5，5 最高
    val timeHorizon: String      = GoalHorizon.MID_TERM.name,
    val progress: Float          = 0f,           // 0.0-1.0
    val isActive: Boolean        = true,
    val relatedProjectId: String? = null,        // 关联项目（§9.1 v3 新增）
    val createdAt: Long          = System.currentTimeMillis(),
    val updatedAt: Long          = System.currentTimeMillis(),
)
