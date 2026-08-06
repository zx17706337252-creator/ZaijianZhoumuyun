package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.AgentPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * AgentPlan DAO — Agent 进化方案（Phase 22）
 */
@Dao
abstract class AgentPlanDao {

    // ── 写入 ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(plan: AgentPlanEntity)

    @Update
    abstract suspend fun update(plan: AgentPlanEntity)

    // ── 归档旧方案（写入新方案前调用）────────────────────────

    @Query("""
        UPDATE agent_plans
        SET isActive = 0, updatedAt = :updatedAt
        WHERE characterId = :characterId AND isActive = 1
    """)
    abstract suspend fun archiveActive(characterId: Int, updatedAt: Long = System.currentTimeMillis())

    // P2-6-5 修复：归档旧方案 + 写入新方案包进同一 @Transaction。此前 PlanSaveTool
    // 先 archiveActive 再 insert，insert 失败时旧方案已被置非激活且不回滚 → 进化方案丢失。
    // 用 abstract class DAO 的 @Transaction open 方法（与 MemoryDao/MemoryTagDao 同款写法，
    // 比 interface default 方法更稳），事务内任一语句失败整体回滚，保证两者要么都成功、
    // 要么都不发生。
    @Transaction
    open suspend fun archiveAndInsert(characterId: Int, plan: AgentPlanEntity) {
        archiveActive(characterId, plan.updatedAt)
        insert(plan)
    }

    // ── 读取：当前有效方案 ────────────────────────────────────

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    abstract suspend fun getActive(characterId: Int): AgentPlanEntity?

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId AND isActive = 1
        ORDER BY updatedAt DESC
        LIMIT 1
    """)
    abstract fun observeActive(characterId: Int): Flow<AgentPlanEntity?>

    // ── 读取：历史方案（UI 展示用）────────────────────────────

    @Query("""
        SELECT * FROM agent_plans
        WHERE characterId = :characterId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    abstract suspend fun getHistory(characterId: Int, limit: Int = 10): List<AgentPlanEntity>

    // ── 删除 ──────────────────────────────────────────────────

    @Query("DELETE FROM agent_plans WHERE id = :planId")
    abstract suspend fun deleteById(planId: String)

    @Query("DELETE FROM agent_plans WHERE characterId = :characterId AND isActive = 0")
    abstract suspend fun deleteArchived(characterId: Int)
}
