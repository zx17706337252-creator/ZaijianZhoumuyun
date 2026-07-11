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

    /** Phase 4：获取指定角色最后一条消息的时间戳，null 表示从未对话 */
    @Query("SELECT MAX(createdAt) FROM messages WHERE characterId = :characterId")
    suspend fun getLastMessageAt(characterId: Int): Long?

    /**
     * Phase 4：获取最近 [sinceMs] 毫秒内有过消息的所有角色 ID。
     * 供 PresenceEngine 检测"用户最近在和其他角色对话"。
     */
    @Query("SELECT DISTINCT characterId FROM messages WHERE createdAt >= :sinceMs")
    suspend fun getRecentCharacterIds(sinceMs: Long): List<Int>
}
