package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 29 · 任务执行结果实体
 *
 * 云端结果从 Supabase 拉取后写入本地（供离线查看）。
 * 本地补跑的结果直接写入本地。
 */
@Entity(
    tableName = "job_results",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["characterId", "isRead"]),
        Index(value = ["createdAt"]),
    ]
)
data class JobResultEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val characterId: Int,
    val toolName: String,
    val status: String = "success",             // "success" / "failed"
    val output: String?,            // 成功时的结果文本
    val errorMessage: String?,      // 失败时的错误描述
    val executedBy: String = "local",         // "cloud" 或 "local"
    val startedAt: Long,
    val completedAt: Long?,
    val isRead: Boolean = false,
    val createdAt: Long,
    // B5 问题2修复：markResultRead 失败时置 false，标记该结果的云端 is_read
    // 状态尚未同步成功，需在下次启动时补重试（见 ScheduleRepository.retryPendingCloudMarkRead）。
    // 默认 true——本地产出的结果（executedBy="local"）不涉及云端已读状态，无需同步。
    val cloudMarkReadSynced: Boolean = true,
)
