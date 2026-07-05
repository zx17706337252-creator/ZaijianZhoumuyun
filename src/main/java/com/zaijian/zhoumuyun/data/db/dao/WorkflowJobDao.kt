package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.WorkflowJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: WorkflowJobEntity)

    @Query("SELECT * FROM workflow_jobs WHERE id = :id")
    suspend fun findById(id: String): WorkflowJobEntity?

    @Query("SELECT * FROM workflow_jobs WHERE status = 'RUNNING'")
    suspend fun findAllRunning(): List<WorkflowJobEntity>

    @Query("SELECT * FROM workflow_jobs WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun observeByCharacter(characterId: Int): Flow<List<WorkflowJobEntity>>

    @Query("SELECT * FROM workflow_jobs WHERE characterId = :characterId AND isReported = 0 AND status != 'RUNNING' ORDER BY createdAt DESC")
    suspend fun findUnreported(characterId: Int): List<WorkflowJobEntity>

    @Query("UPDATE workflow_jobs SET currentStep = :step WHERE id = :id")
    suspend fun updateProgress(id: String, step: Int)

    @Query("UPDATE workflow_jobs SET status = :status, completedAt = :completedAt, resultSummary = :resultSummary, failReason = :failReason WHERE id = :id")
    suspend fun finish(id: String, status: String, completedAt: Long, resultSummary: String?, failReason: String?)

    @Query("UPDATE workflow_jobs SET isReported = 1 WHERE id = :id")
    suspend fun markReported(id: String)
}
