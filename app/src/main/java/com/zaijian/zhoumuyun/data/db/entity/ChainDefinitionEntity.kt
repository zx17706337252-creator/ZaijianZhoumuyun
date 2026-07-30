package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 灵活自动化编排 · 链条定义（新增表：chain_definitions）
 *
 * 对应《灵活自动化编排·改造设计方案》§3.1。一条 [ChainDefinitionEntity] 是一条
 * "自动化规则"：trigger 决定何时触发，nodesJson 描述 Wait/Check/Action/End 节点链。
 * 运行时由 ChainTriggerMatcher 匹配事件后创建 ChainRunEntity 执行。
 *
 * §11.11 范围决策：链条定义不做云端同步（对齐 WorkflowRepository，而非
 * ScheduleRepository），本次改造只做本地持久化。
 *
 * §11.12 范围决策：[characterId] 允许取值 -1（项目级触发），对齐
 * WorkflowStartTool / CiCdStartTool 现有惯例。-1 只是该整数字段的合法取值，
 * 不涉及 schema 变更，已有索引天然覆盖。
 *
 * 建表 SQL 见 Migration72to73.kt（§13.2），String 主键写法对照 workflow_jobs 表，
 * 不是 agent_store_records 的自增主键写法。
 */
@Entity(
    tableName = "chain_definitions",
    indices = [
        Index(value = ["characterId"]),
        // §6 ChainTriggerMatcher 高频查询路径：按事件名 + 是否启用筛选
        Index(value = ["triggerEventName", "enabled"]),
    ],
)
data class ChainDefinitionEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val name: String,                  // 用户/LLM 可读的链条名称，任意描述性文本
    val triggerType: String,           // EVENT | SCHEDULE | MANUAL，见 [ChainTriggerType]
    val triggerEventName: String?,     // triggerType=EVENT 时：监听的事件名，如 "mood_below_threshold"
    val triggerCron: String?,          // triggerType=SCHEDULE 时：复用已有 cron 解析（见 ScheduleCreateTool）
    val nodesJson: String,             // ChainNode 列表的 JSON 序列化，见 ChainNode.kt §3.3
    val enabled: Boolean = true,
    val createdAt: Long,
)

/** [ChainDefinitionEntity.triggerType] 的合法取值。 */
object ChainTriggerType {
    const val EVENT = "EVENT"
    const val SCHEDULE = "SCHEDULE"
    const val MANUAL = "MANUAL"
}
