package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 29 · 本地任务调度实体
 *
 * 与 Supabase scheduled_jobs 表结构保持一致，
 * 供本地补跑（App 打开时补执行云端漏跑的任务）使用。
 */
@Entity(
    tableName = "scheduled_jobs",
    indices = [
        Index(value = ["enabled", "nextRunAt"]),
        Index(value = ["characterId"]),
    ]
)
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val title: String,
    val toolName: String,
    val toolParamsJson: String,         // JSON 序列化的 Map<String, String>
    val enabled: Boolean = true,
    val repeatIntervalMs: Long?,        // null = 一次性任务
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val executedBy: String = "local",   // 本地补跑时记录为 "local"
    val createdAt: Long,
    /** P1-32：云端同步标记（false = 待重试） */
    val cloudSynced: Boolean = true,
    /** P1-33：执行认领锁到期时间（null = 未锁定） */
    val lockedUntil: Long? = null,
)
