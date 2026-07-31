package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.RelationshipDao
import com.zaijian.zhoumuyun.data.db.dao.RelationshipMilestoneDao
import com.zaijian.zhoumuyun.data.db.dao.WorldEventDao
import com.zaijian.zhoumuyun.data.db.entity.EventType
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.data.db.entity.RelationshipMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.WorldEventEntity
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

/**
 * C7#21 修复：查询结果包装类型，让调用方能区分"确实没有数据"和"查询失败拿不到数据"
 * ——此前两种情形都统一表现为空列表，UI 端无法区分，角色详情页在数据库异常时
 * 和"从没发生过关系事件"看起来一模一样（一片空白），用户无从得知是不是出了问题。
 * 只用在 [RelationshipReadRepository] 这两个 suspend 查询上，不影响 Flow 方法
 * （Flow 已用 .catch{} 兜底为 null，语义上 null 本身已经能区分"无/失败"，不需要改）。
 */
sealed class RelQueryResult<out T> {
    data class Success<T>(val data: T) : RelQueryResult<T>()
    data object Failed : RelQueryResult<Nothing>()
}

/**
 * S8-窗口01 修复：只读关系数据 Repository，专供 UI 层（CharacterDetailScreen /
 * CharacterDetailRelationship 的 HeroCard 迷你版 BondRibbon + RelationshipPanel
 * 完整版关系面板）替代此前 Composable 内 `remember { AppDatabase.getInstance(...) }`
 * 裸调用。
 *
 * 与 [com.zaijian.zhoumuyun.domain.RelationshipEngine] 的区别：RelationshipEngine
 * 是承载 applyDelta/衰减/圆桌角色间关系等写路径业务逻辑的领域引擎，不适合直接
 * 暴露给 UI 做纯读查询；这里只包一层最小的只读查询 + 统一错误处理，不含任何
 * 业务规则。
 *
 * 所有方法均内置容错：
 * - Flow 方法用 `.catch{}` 兜底为 null，避免 Room 查询异常（如迁移后 schema
 *   不一致）经 collectAsStateWithLifecycle 传播导致 Composable 重组崩溃。
 * - 挂起函数方法用 try-catch 兜底为空列表，语义等价于"暂无数据"，不阻断
 *   页面渲染（对应报告新发现1：LaunchedEffect 内查询原先无 try-catch 保护）。
 */
class RelationshipReadRepository(
    private val relationshipDao: RelationshipDao,
    private val worldEventDao: WorldEventDao,
    private val milestoneDao: RelationshipMilestoneDao,
) {

    /**
     * 观察 fromId（通常是 "user"）到 toId 这一条关系记录的实时变化。
     * 对应原 CharacterDetailScreen.heroRelFlow / RelationshipPanel.relFlow。
     */
    fun observeRelationTo(fromId: String, toId: String): Flow<RelationshipEntity?> =
        relationshipDao.observeFrom(fromId)
            .map { list -> list.firstOrNull { it.toId == toId } }
            .catch { e ->
                ZLog.e("RelationshipReadRepo", "observeRelationTo($fromId→$toId) 查询失败", e)
                emit(null)
            }
            .flowOn(Dispatchers.IO)

    /**
     * 取 actorId→targetId 之间最近的关系变化事件（RELATIONSHIP_CHANGED 类型）。
     * C7#21 修复：失败时返回 [RelQueryResult.Failed] 而非空列表，调用方（UI）
     * 能明确区分"确实无关系事件"和"查询失败"，不再统一显示成一片空白。
     */
    suspend fun getRecentRelationshipEvents(
        actorId: String,
        targetId: String,
        queryLimit: Int = 8,
    ): RelQueryResult<List<WorldEventEntity>> = try {
        RelQueryResult.Success(
            worldEventDao
                .queryByType(EventType.RELATIONSHIP_CHANGED.name, queryLimit)
                .filter { it.actorId == actorId && it.targetId == targetId }
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e("RelationshipReadRepo", "getRecentRelationshipEvents($actorId→$targetId) 查询失败", e)
        RelQueryResult.Failed
    }

    /**
     * 取 fromId→toId 最近的关系转折点（Milestone）。
     * C7#21 修复：同上，失败时返回 [RelQueryResult.Failed]。
     */
    suspend fun getRecentMilestones(
        fromId: String,
        toId: String,
        limit: Int = 10,
    ): RelQueryResult<List<RelationshipMilestoneEntity>> = try {
        RelQueryResult.Success(milestoneDao.getRecent(fromId, toId, limit))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.e("RelationshipReadRepo", "getRecentMilestones($fromId→$toId) 查询失败", e)
        RelQueryResult.Failed
    }
}
