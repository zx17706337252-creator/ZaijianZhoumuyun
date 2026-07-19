package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_milestones",
    indices = [Index(value = ["projectId"])]
)
data class ProjectMilestoneEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long,
)
