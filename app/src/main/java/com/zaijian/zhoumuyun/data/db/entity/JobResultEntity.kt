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
    val status: String,             // "success" / "failed"
    val output: String?,            // 成功时的结果文本
    val errorMessage: String?,      // 失败时的错误描述
    val executedBy: String,         // "cloud" 或 "local"
    val startedAt: Long,
    val completedAt: Long?,
    val isRead: Boolean = false,
    val createdAt: Long,
)
