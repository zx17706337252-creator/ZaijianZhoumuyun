package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  Project Engine — 数据层（设计方案 §19）
//
//  Phase 9 预建表，Phase 10 实现完整逻辑。
//  三张表：projects / project_milestones / project_members
// ─────────────────────────────────────────────────────────────

enum class ProjectStatus { ACTIVE, PAUSED, COMPLETED, ARCHIVED }

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String     = "",
    val status: String          = ProjectStatus.ACTIVE.name,
    val ownerId: String         = "user",
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?       = null,
    // B5 修复：三层结构 Tasks/Goals/Projects 关联字段；null = 独立项目（不挂载到任何目标）
    val goalId: String?         = null,
)


