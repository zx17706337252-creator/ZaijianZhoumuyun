package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.LearningGoalDao
import com.zaijian.zhoumuyun.data.db.entity.LearningGoalEntity
import kotlinx.coroutines.flow.Flow

/**
 * LearningGoal Repository（审计报告 Phase 3 附带修复：RoundtableViewModel.kt
 * 1015 行 `db.learningGoalDao().getActive(bot.id)` 裸调用治理）。
 *
 * 包装 LearningGoalDao 的全部方法，不只是本次实际用到的 getActive——
 * 与 Message/Identity/AgentPlan 三个 Repository 同一模式，供项目里其他仍裸持有
 * LearningGoalDao 的调用点（ChatViewModel、ZaijianApp、BottomNavBadgeViewModel、
 * LearningGoalViewModel、AgentCoreTools、AgentMetaTools、DistillationEngine、
 * EvaluationEngine 等，本次核查确认这些文件同样存在裸持有情况，但不在本次改动
 * 范围内）未来收敛时直接复用。
 */
class LearningGoalRepository(
    private val goalDao: LearningGoalDao,
) {
    // ── 写入 ──────────────────────────────────────────────────

    suspend fun insert(goal: LearningGoalEntity) = goalDao.insert(goal)
    suspend fun update(goal: LearningGoalEntity) = goalDao.update(goal)

    // ── 读取：激活目标 ────────────────────────────────────────

    suspend fun getActive(characterId: Int): List<LearningGoalEntity> =
        goalDao.getActive(characterId)

    fun observeActive(characterId: Int): Flow<List<LearningGoalEntity>> =
        goalDao.observeActive(characterId)

    // ── 读取：按 ID ───────────────────────────────────────────

    suspend fun getById(goalId: String): LearningGoalEntity? = goalDao.getById(goalId)

    // ── 读取：所有目标（含非激活，UI 列表用）────────────────

    fun observeAll(characterId: Int): Flow<List<LearningGoalEntity>> =
        goalDao.observeAll(characterId)

    // ── 更新进度（goal_update 工具调用）─────────────────────

    suspend fun incrementProgress(
        goalId: String,
        characterId: Int,
        delta: Float,
        note: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ) = goalDao.incrementProgress(goalId, characterId, delta, note, updatedAt)

    // ── 删除 ──────────────────────────────────────────────────

    suspend fun deleteById(goalId: String) = goalDao.deleteById(goalId)

    suspend fun deactivate(goalId: String, updatedAt: Long = System.currentTimeMillis()) =
        goalDao.deactivate(goalId, updatedAt)

    // ── 统计 ──────────────────────────────────────────────────

    suspend fun countActive(characterId: Int): Int = goalDao.countActive(characterId)

    fun observeIncompleteCount(): Flow<Int> = goalDao.observeIncompleteCount()
}
