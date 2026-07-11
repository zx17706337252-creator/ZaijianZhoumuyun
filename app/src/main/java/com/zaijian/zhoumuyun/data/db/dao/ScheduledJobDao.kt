package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.*
import com.zaijian.zhoumuyun.data.db.entity.ScheduledJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: ScheduledJobEntity)

    @Update
    suspend fun update(job: ScheduledJobEntity)

    @Query("SELECT * FROM scheduled_jobs WHERE enabled = 1 AND nextRunAt <= :nowMs ORDER BY nextRunAt ASC")
    suspend fun findDueJobs(nowMs: Long): List<ScheduledJobEntity>

    @Query("SELECT * FROM scheduled_jobs WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun observeByCharacter(characterId: Int): Flow<List<ScheduledJobEntity>>

    @Query("SELECT * FROM scheduled_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScheduledJobEntity>>

    @Query("UPDATE scheduled_jobs SET enabled = 0 WHERE id = :id")
    suspend fun disable(id: String)

    @Query("UPDATE scheduled_jobs SET lastRunAt = :lastRunAt, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun updateRunTime(id: String, lastRunAt: Long, nextRunAt: Long)

    @Query("UPDATE scheduled_jobs SET lockedUntil = :lockExpiry WHERE id = :id AND (lockedUntil IS NULL OR lockedUntil < :claimNow)")
    suspend fun claimJob(id: String, claimNow: Long, lockExpiry: Long): Int

    @Query("UPDATE scheduled_jobs SET lockedUntil = NULL WHERE id = :id")
    suspend fun releaseLock(id: String)

    @Query("DELETE FROM scheduled_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Phase 30 方案二：通过 jobId 反查任务标题，供通知浮层展示 */
    @Query("SELECT * FROM scheduled_jobs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ScheduledJobEntity?

    /** P1-32：查询所有云端同步失败、待重试的任务（与 enabled 状态无关） */
    @Query("SELECT * FROM scheduled_jobs WHERE cloudSynced = 0")
    suspend fun findUnsyncedJobs(): List<ScheduledJobEntity>

    /**
     * 全局日程视图：观察所有启用中的任务，按 nextRunAt 升序。
     * GlobalScheduleViewModel 用于时间轴分组展示。
     */
    @Query("SELECT * FROM scheduled_jobs WHERE enabled = 1 ORDER BY nextRunAt ASC")
    fun observeAllEnabled(): Flow<List<ScheduledJobEntity>>

    /**
     * 全局日程视图：观察指定时间窗口内的所有启用任务（用于日视图/周视图）。
     */
    @Query("""
        SELECT * FROM scheduled_jobs
        WHERE enabled = 1
          AND nextRunAt >= :fromMs
          AND nextRunAt < :toMs
        ORDER BY nextRunAt ASC
    """)
    fun observeInRange(fromMs: Long, toMs: Long): Flow<List<ScheduledJobEntity>>

    /**
     * 全局日程视图：观察指定角色集合、时间窗口内的任务（角色筛选器用）。
     */
    @Query("""
        SELECT * FROM scheduled_jobs
        WHERE enabled = 1
          AND characterId IN (:characterIds)
          AND nextRunAt >= :fromMs
          AND nextRunAt < :toMs
        ORDER BY nextRunAt ASC
    """)
    fun observeInRangeForCharacters(
        characterIds: List<Int>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<ScheduledJobEntity>>

    /** 删除指定角色的所有任务（角色详情个人日程：删除单个角色全部任务） */
    @Query("DELETE FROM scheduled_jobs WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: Int)

    /** P1-32：云端重试成功后，标记该任务已同步 */
    @Query("UPDATE scheduled_jobs SET cloudSynced = 1 WHERE id = :id")
    suspend fun markCloudSynced(id: String)

    /** Phase 30 新增：更新任务核心字段（title / tool / params / interval / nextRunAt） */
    @Query("""
        UPDATE scheduled_jobs
        SET title = :title,
            toolName = :toolName,
            toolParamsJson = :toolParamsJson,
            repeatIntervalMs = :repeatIntervalMs,
            nextRunAt = :nextRunAt
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: String,
        title: String,
        toolName: String,
        toolParamsJson: String,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
    )

    /**
     * Phase 30 新增：列出指定角色在截止时间前将执行的任务。
     * enabledOnly 为 true 时只返回 enabled = 1 的行。
     */
    @Query("""
        SELECT * FROM scheduled_jobs
        WHERE characterId = :characterId
          AND nextRunAt <= :beforeMs
          AND (:enabledOnly = 0 OR enabled = 1)
        ORDER BY nextRunAt ASC
    """)
    suspend fun findByCharacterBefore(
        characterId: Int,
        beforeMs: Long,
        enabledOnly: Boolean,
    ): List<ScheduledJobEntity>
}
