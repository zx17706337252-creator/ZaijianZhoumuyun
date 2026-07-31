package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.agent.AppEvent
import com.zaijian.zhoumuyun.data.agent.EventPublisher
import com.zaijian.zhoumuyun.data.db.dao.CharacterMessageCount
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

    suspend fun insert(message: MessageEntity) {
        dao.insert(message)
        // §6 + §11.1 事件埋点：消息发送事件，供 ChainTriggerMatcher 匹配事件触发型链条。
        // 消息已落库（属于持久化业务操作），走 publishPersistent 先写 PendingEventEntity
        // 再 EventBus.emit()，防止 App 被杀期间事件丢失。
        EventPublisher.publishPersistent(AppEvent(
            name = "message_sent",
            characterId = message.characterId,
            payload = mapOf("messageId" to message.id),
        ))
    }

    suspend fun getByCharacter(characterId: Int, limit: Int = 100): List<MessageEntity> =
        dao.getByCharacter(characterId, limit)

    // C8 #43：LLM 上下文专用，过滤掉假扮期间（speakerContext=NON_OWNER）的消息
    suspend fun getByCharacterForContext(characterId: Int, limit: Int = 100): List<MessageEntity> =
        dao.getByCharacterForContext(characterId, limit)

    // C8 #43：假扮识别判定在消息落库之后才算出结果，算出后回写这一条
    suspend fun updateSpeakerContext(id: String, speakerContext: String) =
        dao.updateSpeakerContext(id, speakerContext)

    suspend fun getRecentByCharacter(characterId: Int, limit: Int = 6): List<MessageEntity> =
        dao.getRecentByCharacter(characterId, limit)

    fun observeByCharacter(characterId: Int): Flow<List<MessageEntity>> =
        dao.observeByCharacter(characterId)

    suspend fun getLatest(limit: Int = 20): List<MessageEntity> = dao.getLatest(limit)

    suspend fun deleteByCharacter(characterId: Int) = dao.deleteByCharacter(characterId)

    suspend fun countByCharacter(characterId: Int): Int = dao.countByCharacter(characterId)

    suspend fun getLastMessageAt(characterId: Int): Long? = dao.getLastMessageAt(characterId)

    suspend fun getRecentCharacterIds(sinceMs: Long): List<Int> = dao.getRecentCharacterIds(sinceMs)

    suspend fun getRecentCharacterMessageCounts(sinceMs: Long): List<CharacterMessageCount> =
        dao.getRecentCharacterMessageCounts(sinceMs)
}
