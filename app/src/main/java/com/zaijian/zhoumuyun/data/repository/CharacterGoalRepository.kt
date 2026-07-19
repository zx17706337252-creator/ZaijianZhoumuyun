package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.CharacterGoalDao
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import kotlinx.coroutines.flow.Flow

/**
 * 角色目标 Repository（阶段2 S-1 收尾：GoalViewModel DI 迁移）。
 *
 * 包装 [CharacterGoalDao]，逐方法透传，不改变任何行为。`GoalViewModel`
 * 原先裸持有 `goalDao = AppDatabase.getInstance(application).characterGoalDao()`，
 * 现改走此层，与 `projectRepo` 一并从 `AppContainer.instance` 取用。
 *
 * 方法集合覆盖 `CharacterGoalDao` 全部接口（不只 GoalViewModel 用到的
 * upsert/observeActive/updateProgress/deactivate/delete），供其他仍裸持有
 * 该 DAO 的调用点（`ChatToolRegistrar`/`ProactiveMessageWorker`/`ZaijianApp`
 * 内构造 `ProjectDailyPlannerTool`/`PresenceEngine` 等处）未来收敛时复用；
 * 本次改动本身只涉及 `GoalViewModel`，不改动这些工具/引擎构造点——它们接受
 * DAO 作为构造参数是既有的既定模式，不在 S-1"ViewModel 绕过 AppContainer"
 * 的范围内。
 */
class CharacterGoalRepository(private val dao: CharacterGoalDao) {

    suspend fun upsert(goal: CharacterGoalEntity) = dao.upsert(goal)

    fun observeActive(characterId: Int): Flow<List<CharacterGoalEntity>> =
        dao.observeActive(characterId)

    suspend fun getAll(characterId: Int): List<CharacterGoalEntity> = dao.getAll(characterId)

    suspend fun getTopGoal(characterId: Int): CharacterGoalEntity? = dao.getTopGoal(characterId)

    suspend fun updateProgress(goalId: String, progress: Float) = dao.updateProgress(goalId, progress)

    suspend fun deactivate(goalId: String) = dao.deactivate(goalId)

    suspend fun delete(goalId: String) = dao.delete(goalId)

    suspend fun getByCharacterAndProject(characterId: Int, projectId: String): CharacterGoalEntity? =
        dao.getByCharacterAndProject(characterId, projectId)

    fun observeByProject(projectId: String): Flow<List<CharacterGoalEntity>> =
        dao.observeByProject(projectId)
}
