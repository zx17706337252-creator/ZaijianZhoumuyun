package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 结构化存储：单条记录。
 *
 * 设计取舍：不做"每类数据一张表"（那是应用层功能表的做法，如 ScheduleEntity/
 * TaskEntity），而是做一张通用记录表，靠 collection 字段做逻辑分组——因为
 * agent 存什么形状的数据是运行时由 LLM 决定的，不能像 schedule/task 那样
 * 提前在 App 里定义好列。
 *
 * collection 类比"表名"，key 类比"主键"，value 是 JSON，valueType 标注
 * value 的顶层 JSON 类型（string/number/boolean/object/array），用于查询时
 * 决定走哪条比较路径（见 AgentStoreDao 的排序/过滤查询）。
 */
@Entity(
    tableName = "agent_store_records",
    indices = [
        Index(value = ["ownerCharacterId", "collection", "key"], unique = true),
        Index(value = ["ownerCharacterId", "collection", "updatedAt"]),
    ],
)
data class AgentStoreRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 记录归属角色 ID。-1 表示跨角色共享（project 级，语义对齐 VaultIo 的 shared/project）。 */
    val ownerCharacterId: Int,

    /** 逻辑分组名，如 "budget_items"、"reading_list"、"watch_conditions"。LLM 自定义，无需预先注册。 */
    val collection: String,

    /** 记录内业务主键，如 "2026-07"、"item_003"。同一 (owner, collection, key) 唯一，写入即更新（upsert 语义）。 */
    val key: String,

    /** 记录内容，JSON 字符串。顶层可以是 object 也可以是标量。 */
    val valueJson: String,

    /** value 顶层 JSON 类型，见 [AgentStoreValueType]。用于范围查询时决定比较方式，避免对 object/array 做无意义的数值/字典序比较。 */
    val valueType: String,

    val createdAt: Long,
    val updatedAt: Long,
)

/** [AgentStoreRecordEntity.valueType] 的合法取值。 */
object AgentStoreValueType {
    const val STRING = "string"
    const val NUMBER = "number"
    const val BOOLEAN = "boolean"
    const val OBJECT = "object"
    const val ARRAY = "array"
}
