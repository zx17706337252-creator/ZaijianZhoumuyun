package com.zaijian.zhoumuyun.data.agent

/**
 * 灵活自动化编排 · 应用事件（§6 EventBus）
 *
 * 一个 [AppEvent] 代表系统中发生的一件"有意义的事"——消息发送、心情值更新、
 * 任务完成等。由各业务代码位置通过 [EventBus.emit] 发出，由 [ChainTriggerMatcher]
 * 常驻订阅并匹配 [com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity]。
 *
 * @param name         事件名，如 "message_sent"、"mood_updated"、"task_completed"。
 *                     与 ChainDefinitionEntity.triggerEventName 匹配。
 * @param characterId  事件所属角色 ID。§11.12：ChainTriggerMatcher 匹配时除了匹配
 *                     该角色的链条定义，也一并匹配 characterId=-1 的项目级定义。
 * @param payload      事件附带的数据，会预填入 ChainRunEntity.context 供 ConditionEvaluator
 *                     读取。值类型限于 String/Int/Long/Double/Boolean/JSONObject/JSONArray，
 *                     与 ConditionEvaluator 支持的字面量类型对齐。
 */
data class AppEvent(
    val name: String,
    val characterId: Int,
    val payload: Map<String, Any?> = emptyMap(),
)
