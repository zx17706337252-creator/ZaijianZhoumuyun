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

    /**
     * P2-3-2 修复：原查询无 LIMIT，跨角色聚合会把全表任务读进内存。
     * 加 LIMIT 上限，任务堆积时只取最近创建的一批（按 createdAt 倒序）。
     */
    @Query("SELECT * FROM scheduled_jobs ORDER BY createdAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<ScheduledJobEntity>>

    @Query("UPDATE scheduled_jobs SET enabled = 0 WHERE id = :id")
    suspend fun disable(id: String)

    @Query("UPDATE scheduled_jobs SET lastRunAt = :lastRunAt, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun updateRunTime(id: String, lastRunAt: Long, nextRunAt: Long)

    /**
     * W1-006 修复：更新调度时间时，nextRunAt 由 SQL 用数据库中"当前"的
     * repeatIntervalMs 现算（lastRunAt + repeatIntervalMs），而不是由调用方
     * （ScheduledJobWorker）传入 Worker 读取任务时内存里的旧值。
     *
     * 原问题：Worker 在 doWork() 开头读一次 job（含 repeatIntervalMs），执行完
     * 工具后用这个内存中的旧值算 nextRunAt 并调用 updateRunTime。如果用户在
     * Worker 读取之后、写回之前，通过 UI（ScheduleRepository.updateJob() →
     * updateFields()）修改了 repeatIntervalMs，Worker 写回时会用旧间隔覆盖，
     * 用户的修改被静默丢弃。
     *
     * 修复后：nextRunAt 完全由这条 UPDATE 语句在数据库当前行上现算，不依赖
     * Worker 内存中的 job 快照，天然不存在"用旧值覆盖新值"的窗口——无论用户
     * 何时修改 repeatIntervalMs，只要修改先于本次 UPDATE 提交，就会用新值。
     *
     * 仅适用于重复任务（repeatIntervalMs 非空）；一次性任务仍走 [disable]。
     */
    @Query("""
        UPDATE scheduled_jobs
        SET lastRunAt = :lastRunAt, nextRunAt = :lastRunAt + repeatIntervalMs
        WHERE id = :id AND repeatIntervalMs IS NOT NULL
    """)
    suspend fun updateRunTimeUsingCurrentInterval(id: String, lastRunAt: Long): Int

    @Query("UPDATE scheduled_jobs SET lockedUntil = :lockExpiry WHERE id = :id AND (lockedUntil IS NULL OR lockedUntil <= :claimNow)")
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

    /**
     * Phase 30 新增：更新任务核心字段（title / tool / params / interval / nextRunAt）。
     *
     * 日程系统批次1扩展：新增 `description` 形参，SQL 里 `description = :description`
     * 直接赋值，不使用 COALESCE 做空值保留。"是否保留旧 description"的判断在上一层
     * （ScheduleRepository.updateJob 的调用方，如 ScheduleUpdateTool）做——传到这里
     * 的就是最终要落地的值（如果想保留旧值，调用方应先读出 existing.description
     * 再原样传回来）。默认 null 仅用于签名向后兼容现有调用方（本批现有调用方均未
     * 接入工单型任务，传入 null 即写空，与历史行为等价，因为 description 是新列）。
     *
     * 日程系统第七节扩展：新增 `projectId` 形参，与 description 同款直接赋值语义。
     * 调用方若想保留旧 projectId，需先读出 existing.projectId 原样传回（见
     * ScheduleUpdateTool 未传 project_id 时的处理）。
     */
    @Query("""
        UPDATE scheduled_jobs
        SET title = :title, toolName = :toolName, toolParamsJson = :toolParamsJson,
            repeatIntervalMs = :repeatIntervalMs, nextRunAt = :nextRunAt,
            description = :description,
            projectId = :projectId,
            cloudSynced = 0
        WHERE id = :id
    """)
    suspend fun updateFields(
        id: String,
        title: String,
        toolName: String,
        toolParamsJson: String,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
        description: String? = null,
        projectId: String? = null,
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
