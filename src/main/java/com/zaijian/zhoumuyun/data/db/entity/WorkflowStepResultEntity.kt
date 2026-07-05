package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workflow_step_results",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["jobId", "stepIndex"]),
    ]
)
data class WorkflowStepResultEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val stepIndex: Int,
    val toolName: String?,
    val toolParamsJson: String = "{}",
    val success: Boolean,
    val output: String?,
    val errorMessage: String?,
    val decidedNextAction: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val createdAt: Long,
)
