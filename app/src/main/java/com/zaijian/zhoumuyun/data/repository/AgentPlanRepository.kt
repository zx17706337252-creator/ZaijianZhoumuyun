package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.AgentPlanDao
import com.zaijian.zhoumuyun.data.db.entity.AgentPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * Agent 进化方案 Repository（Phase 3 修复手册第3条）。
 *
 * 包装 [AgentPlanDao]，逐方法透传，不改变任何行为。`ChatViewModel`/
 * `RoundtableViewModel` 原先裸持有 `agentPlanDao` 字段直接调用
 * `.getActive()`，现在改走这层薄包装，同时补齐 DAO 全部接口供未来使用。
 */
class AgentPlanRepository(private val dao: AgentPlanDao) {

    suspend fun insert(plan: AgentPlanEntity) = dao.insert(plan)

    suspend fun update(plan: AgentPlanEntity) = dao.update(plan)

    suspend fun archiveActive(characterId: Int, updatedAt: Long = System.currentTimeMillis()) =
        dao.archiveActive(characterId, updatedAt)

    // P2-6-5 修复：归档 + 写入合并为单个 @Transaction，避免 insert 失败时旧方案已归档且不回滚。
    suspend fun archiveAndInsert(characterId: Int, plan: AgentPlanEntity) =
        dao.archiveAndInsert(characterId, plan)

    suspend fun getActive(characterId: Int): AgentPlanEntity? = dao.getActive(characterId)

    fun observeActive(characterId: Int): Flow<AgentPlanEntity?> = dao.observeActive(characterId)

    suspend fun getHistory(characterId: Int, limit: Int = 10): List<AgentPlanEntity> =
        dao.getHistory(characterId, limit)

    suspend fun deleteById(planId: String) = dao.deleteById(planId)

    suspend fun deleteArchived(characterId: Int) = dao.deleteArchived(characterId)
}
