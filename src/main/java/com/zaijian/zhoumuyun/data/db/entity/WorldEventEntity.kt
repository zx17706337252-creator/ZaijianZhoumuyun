package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Event Engine 核心表——系统唯一事实来源（Single Source of Truth）。
 *
 * 设计原则：
 * - 所有写操作必须产生 Event，禁止绕过 Event 直接写其他状态。
 * - 删除 Presence 表后能根据 Event 重建；删除 Timeline 后同理。
 *
 * ⚠️ v3 强调：字段在 Phase 7 就建完整（含 projectId / importance），
 * 即使当前阶段不写入值，避免后期 Room Migration 的麻烦。
 */
@Entity(
    tableName = "world_events",
    indices = [
        Index(value = ["actorId"]),
        Index(value = ["domain"]),
        Index(value = ["projectId"]),
        Index(value = ["createdAt"]),
        Index(value = ["type", "createdAt"]),
    ]
)
data class WorldEventEntity(
    @PrimaryKey val id: String,
    /** EventType.name */
    val type: String,
    /** 发起方：角色 ID 字符串或 "user" */
    val actorId: String?,
    /** 目标方：角色 ID 字符串，可空 */
    val targetId: String?,
    /** EventDomain.name */
    val domain: String,
    /**
     * 关联项目 ID（可空）。
     * ★ v3 新增字段，Phase 10 Project Engine 使用。
     */
    val projectId: String?,
    /** JSON 格式的具体事件数据 */
    val payload: String,
    /**
     * 重要度 1-5，影响是否进入 Memory。
     * ★ v3 新增字段，Phase 8 Memory Engine 使用。
     * 当前阶段默认 2。
     */
    val importance: Int = 2,
    /** 毫秒时间戳 */
    val createdAt: Long,
)

// ─────────────────────────────────────────────────────────────
//  EventType — 所有支持的事件类型
// ─────────────────────────────────────────────────────────────

enum class EventType {
    MESSAGE,              // 用户发消息（Phase 7 开始使用）
    MEMORY_CREATED,       // 新记忆生成（Phase 8）
    MEMORY_UPDATED,       // 记忆更新（Phase 8）
    PROJECT_CREATED,      // 项目创建（Phase 10）
    PROJECT_UPDATED,      // 项目状态推进（Phase 10）
    PROJECT_MILESTONE,    // 项目里程碑达成（Phase 10）
    TASK_CREATED,         // 任务创建（Phase 11）
    TASK_COMPLETED,       // 任务完成（Phase 11）
    TASK_FAILED,          // 任务失败（Phase 11）
    TASK_CANCELLED,       // 任务取消（Fix-13-3）
    RELATIONSHIP_CHANGED, // 关系变化（Phase 9）
    PRESENCE_CHANGED,     // 状态变化（Phase 8）
    CHARACTER_DISCUSSION, // 角色间讨论（Phase 10）
    WORLD_SIMULATION,     // 世界模拟产生的事件（Phase 10）
    SYSTEM,               // 系统事件
}

// ─────────────────────────────────────────────────────────────
//  EventDomain — 事件所属领域
// ─────────────────────────────────────────────────────────────

enum class EventDomain {
    PERSONAL,  // 用户偏好、关系、情绪、长期记忆
    WORK,      // 任务、项目、文件、知识库
    WORLD,     // 角色互动、世界模拟、圆桌讨论
}
