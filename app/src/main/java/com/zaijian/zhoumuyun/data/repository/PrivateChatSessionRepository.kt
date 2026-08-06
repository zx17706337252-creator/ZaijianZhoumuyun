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

    // 修复 #5：retry 续接旧 interrupted session 时，把状态改回 in_progress。
    suspend fun markResumed(sessionId: String) = dao.markResumed(sessionId)

    // v2.7：markCompleted 透传方法已删除（死代码，除自身定义外无任何调用方）。
    // 真正生效的"标记完成"落库路径是 PrivateChatSessionAndPairDao.completeSessionAtomic，
    // 它把 session 状态更新与 pair 计数 +1 绑在同一 @Transaction 内。这里保留过的
    // markCompleted 会绕开那个原子事务保护，误导未来开发者以为它是安全可调用的。

    suspend fun get(sessionId: String): PrivateChatSessionEntity? = dao.get(sessionId)

    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity> =
        dao.getAllByPair(pairId)

    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>> =
        dao.observeByPair(pairId)

    // 修复：按角色查询近期私聊会话，用于主对话 prompt 注入。
    // 私聊实时同步修复后：播报主路径已切换到 getUnnotifiedByCharacter，
    // 此方法保留供其他仍需要"近期"语义的调用方使用。
    suspend fun getRecentByCharacter(
        characterId: Int,
        sinceTimestamp: Long,
        limit: Int = 5,
    ): List<PrivateChatSessionEntity> =
        dao.getRecentByCharacter(characterId, sinceTimestamp, limit)

    // 私聊实时同步修复：按"未告知"查询该角色尚未被播报过的私聊会话，不依赖时间窗口。
    suspend fun getUnnotifiedByCharacter(
        characterId: Int,
        limit: Int = 5,
    ): List<PrivateChatSessionEntity> =
        dao.getUnnotifiedByCharacter(characterId, limit)

    // 私聊实时同步修复：标记某个角色已经在其主对话里被播报过这次会话。
    suspend fun markNotified(sessionId: String, characterId: Int) =
        dao.markNotified(sessionId, characterId)
}
