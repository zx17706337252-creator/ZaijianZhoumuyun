package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflow_jobs",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["status"]),
        Index(value = ["characterId", "status"]),
        Index(value = ["isReported", "status"]),
    ]
)
data class WorkflowJobEntity(
    @PrimaryKey val id: String,
    val characterId: Int,
    val goal: String,
    val status: String = "RUNNING",
    val currentStep: Int = 0,
    val maxSteps: Int = 8,
    val resultSummary: String? = null,
    val failReason: String? = null,
    val startedAt: Long,
    val deadlineAt: Long,
    val completedAt: Long? = null,
    val isReported: Boolean = false,
    val createdAt: Long,
)
