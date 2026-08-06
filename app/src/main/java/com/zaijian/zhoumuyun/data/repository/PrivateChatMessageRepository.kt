package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PrivateChatMessageDao
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 私聊消息 Repository（方案_角色间私聊_v2-5 3.2 节）
 *
 * 薄封装，逐方法透传 DAO，与 RoundtableMessageRepository 风格一致。
 */
class PrivateChatMessageRepository(private val dao: PrivateChatMessageDao) {

    suspend fun insert(message: PrivateChatMessageEntity) = dao.insert(message)

    /**
     * 验收修复：取"最近 N 条"，按会话内时间正序返回（调用方
     * PrivateChatEngine.generateReply() 需要正序历史来映射 LLMMessage 列表）。
     * DAO 层按 timestamp DESC 取最近 N 条（保证 limit 截断的是最新的消息，
     * 不是最老的），这里 reversed() 翻回正序，不改变调用方看到的契约。
     */
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<PrivateChatMessageEntity> =
        dao.getRecentBySessionDesc(sessionId, limit).reversed()

    suspend fun getAllByPair(pairId: String): List<PrivateChatMessageEntity> =
        dao.getAllByPair(pairId)

    fun observeByPair(pairId: String): Flow<List<PrivateChatMessageEntity>> =
        dao.observeByPair(pairId)

    fun observeBySession(sessionId: String): Flow<List<PrivateChatMessageEntity>> =
        dao.observeBySession(sessionId)

    // 修复 #5：续接 interrupted session 时用，取该 session 全部消息（正序）。
    suspend fun getAllBySession(sessionId: String): List<PrivateChatMessageEntity> =
        dao.getAllBySession(sessionId)

    suspend fun countByPair(pairId: String): Int = dao.countByPair(pairId)
}
