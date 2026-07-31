package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    /** 获取指定角色的历史消息（按时间升序，最多 limit 条） */
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getByCharacter(characterId: Int, limit: Int = 100): List<MessageEntity>

    /**
     * C8 #43 收尾：喂给 LLM 的对话历史专用查询，排除 speakerContext=NON_OWNER
     * 的消息（owner 在本角色窗口假扮第三方期间产生的对话）——避免角色把假扮期间
     * 说的话当成"主人本人说的"带入后续正常对话联想。与 [getByCharacter] 的区别
     * 仅多一层 WHERE，UI 展示（ChatSessionDelegate.loadMessages）不走这个方法，
     * 用户自己发的消息该原样可见，不受此过滤影响。
     */
    @Query("SELECT * FROM messages WHERE characterId = :characterId AND speakerContext != 'NON_OWNER' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getByCharacterForContext(characterId: Int, limit: Int = 100): List<MessageEntity>

    /**
     * C8 #43 写入侧收尾：消息落库时 speakerContext 判定还没算出来（假扮识别在
     * 落库之后才跑），先按默认值 OWNER_DIRECT 落库，判定结果出来后回写这一条。
     * 只在 NON_OWNER 时才需要调用（OWNER_DIRECT 是默认值，不用额外写）。
     */
    @Query("UPDATE messages SET speakerContext = :speakerContext WHERE id = :id")
    suspend fun updateSpeakerContext(id: String, speakerContext: String)

    /**
     * 获取指定角色最近 [limit] 条消息，按时间倒序返回（最新的在最前）。
     * 与 [getByCharacter] 的区别：后者是 ASC + LIMIT，消息数超过 limit 时拿到的
     * 是最早的记录而非最近的；此方法专门用于"只关心最近聊了什么"的场景
     * （如主动消息 AI 生成需要参考近期话题），调用方需要按时间顺序展示时
     * 自行 reversed()。
     *
     * C8 #43：目前唯一调用方（PresenceEngine 主动消息生成）就是喂给 LLM 的场景，
     * 直接加 speakerContext 过滤，不再另开一份方法。
     */
    @Query("SELECT * FROM messages WHERE characterId = :characterId AND speakerContext != 'NON_OWNER' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByCharacter(characterId: Int, limit: Int = 6): List<MessageEntity>

    /** 实时观察指定角色的消息列表（UI 层用） */
    @Query("SELECT * FROM messages WHERE characterId = :characterId ORDER BY createdAt ASC")
    fun observeByCharacter(characterId: Int): Flow<List<MessageEntity>>

    /** 获取最近 N 条（跨角色，用于 Event 上下文） */
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getLatest(limit: Int = 20): List<MessageEntity>

    @Query("DELETE FROM messages WHERE characterId = :characterId")
    suspend fun deleteByCharacter(characterId: Int)

    @Query("SELECT COUNT(*) FROM messages WHERE characterId = :characterId")
    suspend fun countByCharacter(characterId: Int): Int

    // ── 批次8 8-1修复：定期清理方法（messages 只增不删）──────────

    /** 删除 [beforeMs] 时间戳之前的所有消息（按时间裁剪，保留最近 N 天）。 */
    @Query("DELETE FROM messages WHERE createdAt < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    /**
     * 每个角色只保留最近 [keepPerCharacter] 条消息，其余删除（按角色裁剪）。
     * 用子查询实现：保留每 characterId 按 createdAt DESC 排序的前 keepPerCharacter 条 id，
     * 其余全部删除。平局用 id 破除（createdAt 相同时 id 大的视为较新）。
     */
    @Query("""
        DELETE FROM messages
        WHERE id NOT IN (
            SELECT id FROM messages m1
            WHERE (SELECT COUNT(*) FROM messages m2
                   WHERE m2.characterId = m1.characterId
                     AND (m2.createdAt > m1.createdAt
                          OR (m2.createdAt = m1.createdAt AND m2.id > m1.id))
            ) < :keepPerCharacter
        )
    """)
    suspend fun trimByCharacter(keepPerCharacter: Int): Int

    /** Phase 4：获取指定角色最后一条消息的时间戳，null 表示从未对话 */
    @Query("SELECT MAX(createdAt) FROM messages WHERE characterId = :characterId")
    suspend fun getLastMessageAt(characterId: Int): Long?

    /**
     * 角标 Flow 化改造第1步：与 getLastMessageAt 同一条 SQL 的 Flow 版本，
     * 供 BriefingRepository.observeAttentionItems() 订阅使用。该角色任意
     * 一条消息的增删都会触发这里重新查询。
     */
    @Query("SELECT MAX(createdAt) FROM messages WHERE characterId = :characterId")
    fun observeLastMessageAt(characterId: Int): Flow<Long?>

    /**
     * Phase 4：获取最近 [sinceMs] 毫秒内有过消息的所有角色 ID。
     * 供 PresenceEngine 检测"用户最近在和其他角色对话"。
     *
     * 注：JEALOUSY 判定已改用 getRecentCharacterMessageCounts()（按消息数量门槛
     * 过滤"聊得多"而非"聊过"），此方法暂无调用方，保留供其他潜在场景使用。
     */
    @Query("SELECT DISTINCT characterId FROM messages WHERE createdAt >= :sinceMs")
    suspend fun getRecentCharacterIds(sinceMs: Long): List<Int>

    /**
     * 主动消息 JEALOUSY 判定收紧：原先只要 [sinceMs] 内有过一条消息就算
     * "用户最近在和其他角色对话"，导致触发过泛（哪怕只聊了一句也会让
     * 所有其他角色判定吃醋）。改为返回每个角色在窗口内的消息数，
     * 由调用方按数量门槛过滤，只有"聊得比较多"才真正算作吃醋诱因。
     */
    @Query("SELECT characterId, COUNT(*) as messageCount FROM messages WHERE createdAt >= :sinceMs GROUP BY characterId")
    suspend fun getRecentCharacterMessageCounts(sinceMs: Long): List<CharacterMessageCount>
}

/**
 * [MessageDao.getRecentCharacterMessageCounts] 的行映射：某角色在窗口期内的消息数。
 */
data class CharacterMessageCount(
    val characterId: Int,
    val messageCount: Int,
)
