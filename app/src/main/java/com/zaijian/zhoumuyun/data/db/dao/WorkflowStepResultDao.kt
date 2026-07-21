package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowStepResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: WorkflowStepResultEntity)

    @Query("SELECT * FROM workflow_step_results WHERE jobId = :jobId ORDER BY stepIndex ASC")
    suspend fun findByJob(jobId: String): List<WorkflowStepResultEntity>

    // Window B（「心迹」合并视图，方案 2.2.2）：workflow_step_results 本身没有
    // characterId 列，按角色聚合需 INNER JOIN workflow_jobs（持有 characterId）。
    // 纯只读 @Query，不改动任何表结构/迁移，与既有 findByJob 共存。
    // 供 AgentActivityRepository.observeTimeline() 把工作流步骤并入统一时间线，
    // 避免面板 UI 层自己拼两张表。
    @Query(
        "SELECT s.* FROM workflow_step_results s " +
            "INNER JOIN workflow_jobs j ON s.jobId = j.id " +
            "WHERE j.characterId = :characterId " +
            "ORDER BY s.createdAt ASC"
    )
    fun observeStepsByCharacter(characterId: Int): Flow<List<WorkflowStepResultEntity>>
}
