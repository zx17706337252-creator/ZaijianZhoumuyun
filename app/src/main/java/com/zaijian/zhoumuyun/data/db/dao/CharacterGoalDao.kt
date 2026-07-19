package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterGoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: CharacterGoalEntity)

    /** 观察某角色所有激活目标，按优先级降序 */
    @Query("""
        SELECT * FROM character_goals
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY priority DESC, updatedAt DESC
    """)
    fun observeActive(characterId: Int): Flow<List<CharacterGoalEntity>>

    /** 取某角色所有目标（含已完成），用于 Prompt 注入和 World Simulation */
    @Query("SELECT * FROM character_goals WHERE characterId = :characterId ORDER BY priority DESC")
    suspend fun getAll(characterId: Int): List<CharacterGoalEntity>

    /** 取最高优先级的激活目标，用于 Presence Engine 生成状态 */
    @Query("""
        SELECT * FROM character_goals
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY priority DESC LIMIT 1
    """)
    suspend fun getTopGoal(characterId: Int): CharacterGoalEntity?

    @Query("UPDATE character_goals SET progress = :progress, updatedAt = :now WHERE id = :goalId")
    suspend fun updateProgress(goalId: String, progress: Float, now: Long = System.currentTimeMillis())

    @Query("UPDATE character_goals SET isActive = 0, updatedAt = :now WHERE id = :goalId")
    suspend fun deactivate(goalId: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM character_goals WHERE id = :goalId")
    suspend fun delete(goalId: String)

    // ── Step 2：relatedProjectId 激活 ────────────────────────

    /**
     * 取某角色在某项目下的阶段目标（ProjectDailyPlannerTool 用于拼上下文）。
     * relatedProjectId 字段自 v2→v3 建表起已存在，但索引此前从未创建
     * （W1 审查发现），已在 MIGRATION_53_54 中补建 index_character_goals_relatedProjectId。
     */
    @Query("""
        SELECT * FROM character_goals
        WHERE characterId = :characterId AND relatedProjectId = :projectId AND isActive = 1
        ORDER BY priority DESC LIMIT 1
    """)
    suspend fun getByCharacterAndProject(characterId: Int, projectId: String): CharacterGoalEntity?

    /**
     * 观察某项目下所有角色的目标（成长中心页项目区块数据源）。
     */
    @Query("""
        SELECT * FROM character_goals
        WHERE relatedProjectId = :projectId AND isActive = 1
        ORDER BY characterId ASC, priority DESC
    """)
    fun observeByProject(projectId: String): Flow<List<CharacterGoalEntity>>
}
