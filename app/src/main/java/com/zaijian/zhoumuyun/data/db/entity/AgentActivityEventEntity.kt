package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 过程可见层（UI 展示名「心迹」）的事件实体。
 *
 * 见《Window B 执行方案 v1.1》2.2.2。本表是 Window B 的核心新增数据载体，
 * 统一承载"Agent 刚才做了什么、为什么这么做"的过程痕迹，供「心迹」面板按
 * 时间线呈现。降级策略状态机（2.1）与三处 UI 集成点（2.2.3）、WorkflowEngine
 * 镜像埋点（2.1.4）**共用同一张事件表**，不是两套独立数据。
 *
 * ## 为什么不复用 WorkflowStepResultEntity
 *
 * 字段语义不完全兼容：[sceneType]/[attemptIndex]/[decisionNote] 是新概念，
 * `WorkflowStepResultEntity` 是为工作流单步设计的，塞进去会让原本干净的表
 * 混入非工作流场景的行，且要改 Window A/E 未来可能依赖的既有 schema。详见
 * 方案 2.2.2「也不建议把两张表合并成一张」一段。两张表在查询层
 * （[com.zaijian.zhoumuyun.data.repository.AgentActivityRepository] 的合并视图）
 * 做 UNION 呈现，不在存储层合并。
 *
 * ## 字段说明
 *
 * - [id]：UUID，主键。
 * - [sessionRef]：关联到具体一次回复。私聊= messageId；圆桌被动回复=
 *   roundtableMessageId；圆桌闲时= roundtableMessageId；工作流= workflowJobId。
 *   非外键（不强制级联），只是用于"按某次回复聚合查看"的检索线索。
 * - [sceneType]：`"chat"` / `"roundtable_bot"` / `"roundtable_idle"` / `"workflow"`，
 *   对应 2.2.3 三处集成点 + 2.1.4 工作流镜像。
 * - [eventType]：`"TOOL_CALL"` / `"DEGRADE_RETRY"` / `"DEGRADE_SWITCH"` /
 *   `"DEGRADE_GIVEUP"`；为 Window C 预留 `"SKILL_CREATE"` / `"SKILL_INVOKE"`
 *   占位（本轮不用，字段已预留，避免以后重建一套可见时间线）。
 * - [attemptIndex]：降级链路内第几次尝试，正常单次成功恒为 0。
 * - [outputSummary]：截断摘要（≤300 字），不存全文——全文已经在
 *   messages/workflow_step_results 里有一份，这里只是索引用。
 * - [decisionNote]：可选决策依据（如"上次同参数超时，改用 xxx 参数重试"），
 *   对应「心迹」"为什么这么做"那一层。
 *
 * ## 与北极星原则的关系
 *
 * 本表本身不涉及机械化判断，是纯过程记录载体，不违反任何北极星原则。
 */
@Entity(
    tableName = "agent_activity_events",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "createdAt"]),
        Index(value = ["sessionRef"]),
        Index(value = ["eventType"]),
    ]
)
data class AgentActivityEventEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val sessionRef: String,
    val sceneType: String,
    val eventType: String,
    val toolName: String?,
    val toolParamsJson: String? = "{}",
    val attemptIndex: Int = 0,
    val outcome: String?,
    val outputSummary: String?,
    val errorMessage: String?,
    val decisionNote: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val createdAt: Long,
)
