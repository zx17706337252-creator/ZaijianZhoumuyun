package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_members",
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["characterId"]),
    ]
)
data class ProjectMemberEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val characterId: String,
    val role: String = "CONTRIBUTOR",
    val joinedAt: Long,
)
