package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PrivateChatSessionDao
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 私聊会话状态 Repository（方案_角色间私聊_v2-5 3.2.1 节，v2.3 新增）
 *
 * 薄封装，逐方法透传 DAO，与 RoundtableMessageRepository 风格一致。
 */
class PrivateChatSessionRepository(private val dao: PrivateChatSessionDao) {

    suspend fun insert(session: PrivateChatSessionEntity) = dao.insert(session)

    suspend fun markInterrupted(sessionId: String, turnCount: Int, errorMessage: String) =
        dao.markInterrupted(sessionId, turnCount, errorMessage)

    // v2.7：markCompleted 透传方法已删除（死代码，除自身定义外无任何调用方）。
    // 真正生效的"标记完成"落库路径是 PrivateChatSessionAndPairDao.completeSessionAtomic，
    // 它把 session 状态更新与 pair 计数 +1 绑在同一 @Transaction 内。这里保留过的
    // markCompleted 会绕开那个原子事务保护，误导未来开发者以为它是安全可调用的。

    suspend fun get(sessionId: String): PrivateChatSessionEntity? = dao.get(sessionId)

    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity> =
        dao.getAllByPair(pairId)

    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>> =
        dao.observeByPair(pairId)
}
