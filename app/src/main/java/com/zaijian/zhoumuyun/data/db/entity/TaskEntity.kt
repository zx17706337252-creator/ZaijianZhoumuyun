package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Task Engine — 任务持久化实体（Phase 19）
 *
 * 代表一次由 Agent 执行的任务记录。
 *
 * 任务生命周期：
 *   PENDING → RUNNING → COMPLETED / FAILED / CANCELLED
 *
 * 任务来源：
 *   - 对话中 LLM 触发工具调用（chat_tool）
 *   - 用户在任务中心手动创建（manual）
 *   - 圆桌讨论触发（roundtable）
 *
 * 与 Event Engine 的关系：
 *   - 创建任务时产生 TASK_CREATED 事件
 *   - 完成时产生 TASK_COMPLETED 事件（触发 MemoryCandidate 生成）
 *   - 失败时产生 TASK_FAILED 事件
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["status"]),
        Index(value = ["projectId"]),
        Index(value = ["createdAt"]),
        Index(value = ["status", "createdAt"]),
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,

    /** 任务标题（工具名或用户描述） */
    val title: String,

    /** 任务详细描述（工具参数摘要或用户输入） */
    val description: String,

    /** 执行角色 ID（1-9，与 CharacterConfig 对应） */
    val characterId: Int,

    /** 任务状态 */
    val status: String,         // TaskStatus.name

    /** 完成进度 0.0–1.0 */
    val progress: Float = 0f,

    /** 工具类型（可空，手动任务为 null） */
    val toolName: String? = null,

    /** 任务结果摘要（成功时填入，失败时填入错误描述） */
    val resultSummary: String? = null,

    /** 关联项目 ID（可空） */
    val projectId: String? = null,

    /** 来源（"chat_tool" / "manual" / "roundtable"） */
    val source: String = "chat_tool",

    /** 来源消息 ID（关联到 messages 表，可空） */
    val sourceMessageId: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
)

// ─────────────────────────────────────────────────────────────
//  TaskStatus
// ─────────────────────────────────────────────────────────────

enum class TaskStatus {
    PENDING,     // 等待执行
    RUNNING,     // 执行中
    COMPLETED,   // 已完成
    FAILED,      // 失败
    CANCELLED,   // 已取消
}
