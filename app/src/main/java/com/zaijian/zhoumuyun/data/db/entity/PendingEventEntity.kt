package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 灵活自动化编排 · 待处理事件（§11.1 事件落盘兜底）
 *
 * EventBus（§6）是纯内存的 MutableSharedFlow，App 被系统杀掉时，
 * ChainTriggerMatcher 不在运行，事件直接丢失。对来自持久化业务操作的事件
 * （如消息已落库、心情值已写入 Room），在 emit 之前先写一条 PendingEventEntity，
 * App 重启后由 processPendingEvents() 重放。
 *
 * 对照 BootReceiver.restoreReminderAlarms() 同一模式：ZaijianApp.onCreate()
 * 里查所有 processed=false 的记录，重放给 ChainTriggerMatcher，成功后标记
 * processed=true。
 *
 * 落盘与否取决于事件语义：纯瞬时事件（如"用户正在输入"）不需要落盘，
 * 按纯内存处理即可。
 *
 * 建表 SQL 见 Migration73to74.kt，String 主键写法对照 chain_definitions/chain_runs。
 */
@Entity(
    tableName = "pending_events",
    indices = [
        // processPendingEvents() 的高频查询路径：WHERE processed = 0 ORDER BY createdAt ASC
        Index(value = ["processed"]),
        Index(value = ["eventName"]),
    ],
)
data class PendingEventEntity(
    @PrimaryKey val id: String,
    val eventName: String,              // 对应 AppEvent.name，与 ChainDefinitionEntity.triggerEventName 匹配
    val characterId: Int,               // 对应 AppEvent.characterId
    val payloadJson: String,            // AppEvent.payload 的 JSON 序列化
    val processed: Boolean = false,     // processPendingEvents() 处理后标记 true
    val createdAt: Long,
)
