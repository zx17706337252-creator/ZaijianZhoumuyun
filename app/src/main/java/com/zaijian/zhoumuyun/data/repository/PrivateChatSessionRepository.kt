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

    suspend fun markCompleted(sessionId: String, turnCount: Int) =
        dao.markCompleted(sessionId, turnCount)

    suspend fun get(sessionId: String): PrivateChatSessionEntity? = dao.get(sessionId)

    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity> =
        dao.getAllByPair(pairId)

    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>> =
        dao.observeByPair(pairId)
}
