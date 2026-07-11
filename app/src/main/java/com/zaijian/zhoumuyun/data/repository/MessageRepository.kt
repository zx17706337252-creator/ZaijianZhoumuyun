package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.MessageDao
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 单聊消息 Repository（Phase 3 修复手册第3条）。
 *
 * 包装 [MessageDao]，逐方法透传，不改变任何行为——`ChatViewModel` 原先
 * 裸持有 `messageDao` 字段直接调用 `.upsert()`/`.insert()` 等方法，
 * 现在改走这层薄包装。方法集合覆盖 `MessageDao` 全部接口（不只是
 * `ChatViewModel` 用到的部分），供其他仍裸持有 `db.messageDao()` 的
 * 调用点（如 `WorldSimulation`/`AgentMetaTools`/`ZaijianApp`）未来
 * 收敛时复用；本次改动本身只涉及 `ChatViewModel`，不改动这些调用点。
 */
class MessageRepository(private val dao: MessageDao) {

    suspend fun insert(message: MessageEntity) = dao.insert(message)

    suspend fun getByCharacter(characterId: Int, limit: Int = 100): List<MessageEntity> =
        dao.getByCharacter(characterId, limit)

    fun observeByCharacter(characterId: Int): Flow<List<MessageEntity>> =
        dao.observeByCharacter(characterId)

    suspend fun getLatest(limit: Int = 20): List<MessageEntity> = dao.getLatest(limit)

    suspend fun deleteByCharacter(characterId: Int) = dao.deleteByCharacter(characterId)

    suspend fun countByCharacter(characterId: Int): Int = dao.countByCharacter(characterId)

    suspend fun getLastMessageAt(characterId: Int): Long? = dao.getLastMessageAt(characterId)

    suspend fun getRecentCharacterIds(sinceMs: Long): List<Int> = dao.getRecentCharacterIds(sinceMs)
}
