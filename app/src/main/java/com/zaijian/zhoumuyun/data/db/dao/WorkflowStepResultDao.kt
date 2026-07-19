package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.WorkflowStepResultEntity

@Dao
interface WorkflowStepResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: WorkflowStepResultEntity)

    @Query("SELECT * FROM workflow_step_results WHERE jobId = :jobId ORDER BY stepIndex ASC")
    suspend fun findByJob(jobId: String): List<WorkflowStepResultEntity>
}
