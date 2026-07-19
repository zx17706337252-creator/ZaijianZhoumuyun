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

    // 问题12修复：CI/CD（cicd_start）任务改为固定绑定 characterId=-1（项目级，
    // 见 ChatViewModel.registerCharacterTools() 的注释），不再跟随当前聊天角色。
    // 若仍只按 characterId 精确匹配，-1 的任务在任何角色的聊天窗口里都查不到，
    // 播报功能形同虚设。改为 OR characterId = -1，使当前角色的任务播报（如
    // workflow_start 创建的、有意绑定角色人设的任务）与项目级 CI/CD 任务播报
    // 能够共存：同一次查询里都能被看到，不互相排斥。
    @Query("SELECT * FROM workflow_jobs WHERE (characterId = :characterId OR characterId = -1) AND isReported = 0 AND status != 'RUNNING' ORDER BY createdAt DESC")
    suspend fun findUnreported(characterId: Int): List<WorkflowJobEntity>

    @Query("UPDATE workflow_jobs SET currentStep = :step WHERE id = :id")
    suspend fun updateProgress(id: String, step: Int)

    @Query("UPDATE workflow_jobs SET status = :status, completedAt = :completedAt, resultSummary = :resultSummary, failReason = :failReason WHERE id = :id")
    suspend fun finish(id: String, status: String, completedAt: Long, resultSummary: String?, failReason: String?)

    @Query("UPDATE workflow_jobs SET isReported = 1 WHERE id = :id")
    suspend fun markReported(id: String)
}
