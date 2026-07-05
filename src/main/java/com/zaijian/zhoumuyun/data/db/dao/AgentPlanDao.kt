package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.AgentPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * AgentPlan DAO — Agent 进化方案（Phase 22）
 */
@Dao
interface AgentPlanDao {

    // ── 写入 ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plan: AgentPlanEntity)

    @Update
    suspend fun update(plan: AgentPlanEntity)

    // ── 归档旧方案（写入新方案前调用）────────────────────────

    @Query("""
        UPDATE agent_plans
        SET isActive = 0, updatedAt = :updatedAt
        WHERE characterId = :characterId AND isActive = 1
    """)
    suspend fun archiveActive(characterId: Int, updatedAt: Long = System.currentTimeMillis())

    // ── 读取：当前有效方案 ────────────────────────────────────

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    suspend fun getActive(characterId: Int): AgentPlanEntity?

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    fun observeActive(characterId: Int): Flow<AgentPlanEntity?>

    // ── 读取：历史方案（UI 展示用）────────────────────────────

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getHistory(characterId: Int, limit: Int = 10): List<AgentPlanEntity>

    // ── 删除 ──────────────────────────────────────────────────

    @Query("DELETE FROM agent_plans WHERE id = :planId")
    suspend fun deleteById(planId: String)

    @Query("DELETE FROM agent_plans WHERE characterId = :characterId AND isActive = 0")
    suspend fun deleteArchived(characterId: Int)
}
