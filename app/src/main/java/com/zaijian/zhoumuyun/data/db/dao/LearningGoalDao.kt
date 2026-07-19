package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalEntity
import kotlinx.coroutines.flow.Flow

/**
 * LearningGoal DAO — 学习目标（Phase 22 引入）
 */
@Dao
interface LearningGoalDao {

    // ── 写入 ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goal: LearningGoalEntity)

    @Update
    suspend fun update(goal: LearningGoalEntity)

    // ── 读取：激活目标 ────────────────────────────────────────

    @Query("""
        SELECT * FROM learning_goals
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY createdAt ASC
    """)
    suspend fun getActive(characterId: Int): List<LearningGoalEntity>

    @Query("""
        SELECT * FROM learning_goals
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY createdAt ASC
    """)
    fun observeActive(characterId: Int): Flow<List<LearningGoalEntity>>

    // ── 读取：按 ID ───────────────────────────────────────────

    @Query("SELECT * FROM learning_goals WHERE id = :goalId")
    suspend fun getById(goalId: String): LearningGoalEntity?

    // ── 读取：所有目标（含非激活，UI 列表用）────────────────

    @Query("""
        SELECT * FROM learning_goals
        WHERE characterId = :characterId
        ORDER BY createdAt DESC
    """)
    fun observeAll(characterId: Int): Flow<List<LearningGoalEntity>>

    // ── 更新进度（goal_update 工具调用）─────────────────────

    @Query("""
        UPDATE learning_goals
        SET progress = MIN(1.0, progress + :delta),
            lastUpdateNote = :note,
            status = CASE WHEN MIN(1.0, progress + :delta) >= 1.0 THEN 'COMPLETED' ELSE status END,
            updatedAt = :updatedAt
        WHERE id = :goalId AND characterId = :characterId
    """)
    suspend fun incrementProgress(
        goalId: String,
        characterId: Int,
        delta: Float,
        note: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    // ── 删除 ──────────────────────────────────────────────────

    @Query("DELETE FROM learning_goals WHERE id = :goalId")
    suspend fun deleteById(goalId: String)

    @Query("""
        UPDATE learning_goals
        SET isActive = 0, updatedAt = :updatedAt
        WHERE id = :goalId
    """)
    suspend fun deactivate(goalId: String, updatedAt: Long = System.currentTimeMillis())

    // ── 统计 ──────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM learning_goals WHERE characterId = :characterId AND isActive = 1")
    suspend fun countActive(characterId: Int): Int

    /**
     * M4 修复（BUG-2）：跨角色、响应式的未完成目标计数。
     * 条件：isActive = 1（未归档）且 status ≠ 'COMPLETED'。
     * 供 BottomNavBadgeViewModel 订阅，驱动「成长」Tab 的角标数字。
     */
    @Query("SELECT COUNT(*) FROM learning_goals WHERE isActive = 1 AND status != 'COMPLETED'")
    fun observeIncompleteCount(): Flow<Int>
}
