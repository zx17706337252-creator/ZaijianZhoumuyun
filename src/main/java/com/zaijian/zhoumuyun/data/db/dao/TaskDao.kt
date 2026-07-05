package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.*
import com.zaijian.zhoumuyun.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Task Engine DAO（Phase 19）
 */
@Dao
interface TaskDao {

    // ── 写入 ────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── 状态更新快捷方法 ─────────────────────────────────────

    @Query("""
        UPDATE tasks
        SET status = :status,
            progress = :progress,
            resultSummary = :resultSummary,
            completedAt = :completedAt,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: String,
        status: String,
        progress: Float,
        resultSummary: String?,
        completedAt: Long?,
        updatedAt: Long,
    )

    @Query("UPDATE tasks SET progress = :progress, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, updatedAt: Long)

    /** 2.3 工作台任务跟踪修复：更新任务描述/进度备注，不改变 status。
     *  description 留空时（""）表示不修改原描述，仅更新 progress。 */
    @Query("""
        UPDATE tasks
        SET description = CASE WHEN :description = '' THEN description ELSE :description END,
            progress = :progress,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateDescription(id: String, description: String, progress: Float, updatedAt: Long)

    // ── 查询 ────────────────────────────────────────────────

    /** 观察所有非 CANCELLED 任务，按时间倒序（TaskCenterScreen 主列表） */
    @Query("SELECT * FROM tasks WHERE status != 'CANCELLED' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    /** 观察进行中任务（PENDING / RUNNING） */
    @Query("SELECT * FROM tasks WHERE status IN ('PENDING', 'RUNNING') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<TaskEntity>>

    /** 观察已完成任务 */
    @Query("SELECT * FROM tasks WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<TaskEntity>>

    /** 观察失败任务 */
    @Query("SELECT * FROM tasks WHERE status = 'FAILED' ORDER BY createdAt DESC")
    fun observeFailed(): Flow<List<TaskEntity>>

    /** 按角色查询任务 */
    @Query("SELECT * FROM tasks WHERE characterId = :characterId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByCharacter(characterId: Int, limit: Int = 20): List<TaskEntity>

    /** 按项目查询任务 */
    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByProject(projectId: String, limit: Int = 30): List<TaskEntity>

    /** 查询单条任务 */
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    /** 查询最近 N 条已完成任务（用于 Memory 触发） */
    @Query("SELECT * FROM tasks WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentCompleted(limit: Int = 10): List<TaskEntity>

    /** 任务总数统计 */
    @Query("SELECT COUNT(*) FROM tasks WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    // ── 成长系统查询 ─────────────────────────────────────────

    /**
     * 按角色、项目、来源和时间过滤任务（去重 + 历史查询）。
     * Step 4 ProjectDailyPlannerTool 内部使用：
     *   - 去重：今天是否已规划过（after = todayStart）
     *   - 历史：最近5天的任务（after = now - 5天）
     */
    @Query("""
        SELECT * FROM tasks
        WHERE characterId = :characterId
          AND projectId = :projectId
          AND source = :source
          AND createdAt >= :after
        ORDER BY createdAt DESC
    """)
    suspend fun getByCharacterProjectAndSource(
        characterId: Int,
        projectId: String,
        source: String,
        after: Long,
    ): List<TaskEntity>

    /**
     * 观察指定来源、指定时间之后的任务（Flow，供 TaskViewModel 实时订阅）。
     * P1-A TaskCenterScreen 今日成长任务分组使用。
     */
    @Query("""
        SELECT * FROM tasks
        WHERE source = :source AND createdAt >= :after
        ORDER BY createdAt DESC
    """)
    fun observeBySourceAfter(source: String, after: Long): Flow<List<TaskEntity>>

    /**
     * 观察指定项目、来源、时间范围内的任务（Flow）。
     * P2-A ProjectDetailScreen「今日规划」区块实时订阅用。
     */
    @Query("""
        SELECT * FROM tasks
        WHERE projectId = :projectId
          AND source = :source
          AND createdAt >= :after
        ORDER BY characterId ASC, createdAt ASC
    """)
    fun observeByProjectAndSourceAfter(
        projectId: String,
        source: String,
        after: Long,
    ): Flow<List<TaskEntity>>

    /**
     * 查询指定项目、来源、时间范围内的任务（一次性，非 Flow）。
     * P2-B 成长记录历史摘要计算使用。
     */
    @Query("""
        SELECT * FROM tasks
        WHERE projectId = :projectId
          AND source = :source
          AND createdAt >= :after
        ORDER BY createdAt DESC
    """)
    suspend fun getByProjectAndSourceAfter(
        projectId: String,
        source: String,
        after: Long,
    ): List<TaskEntity>

    /**
     * 轻量勾选：将 project_growth 任务状态在 PENDING↔COMPLETED 之间翻转。
     * P2-A/B TodayGrowthSection 勾选框交互使用。
     */
    @Query("""
        UPDATE tasks
        SET status    = CASE WHEN status = 'COMPLETED' THEN 'PENDING' ELSE 'COMPLETED' END,
            completedAt = CASE WHEN status = 'COMPLETED' THEN NULL ELSE :now END,
            updatedAt = :now
        WHERE id = :id
    """)
    suspend fun toggleGrowthTaskDone(id: String, now: Long)
}
