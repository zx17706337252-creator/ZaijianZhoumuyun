package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivateChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: PrivateChatMessageEntity)

    /**
     * 验收修复：原写法是 `ORDER BY timestamp ASC LIMIT :limit`，取到的是会话里
     * 最早的 N 条，不是最近的 N 条——session 轮数超过 limit 后（maxTurnsPerSession
     * 建议硬上限 12 > 这里的 limit=10），后续轮次拿到的历史会一直停留在会话最开始
     * 那几条，看不到最近的对话内容。改为按 timestamp 倒序取最近 N 条，
     * 结果集本身是倒序的，交给 Repository 层翻回正序再返回。
     */
    @Query("SELECT * FROM private_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentBySessionDesc(sessionId: String, limit: Int): List<PrivateChatMessageEntity>

    @Query("SELECT * FROM private_chat_messages WHERE pairId = :pairId ORDER BY timestamp ASC")
    suspend fun getAllByPair(pairId: String): List<PrivateChatMessageEntity>

    @Query("SELECT * FROM private_chat_messages WHERE pairId = :pairId ORDER BY timestamp ASC")
    fun observeByPair(pairId: String): Flow<List<PrivateChatMessageEntity>>

    @Query("SELECT * FROM private_chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: String): Flow<List<PrivateChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM private_chat_messages WHERE pairId = :pairId")
    suspend fun countByPair(pairId: String): Int

    // A10-5 修复：级联删除——按 pairId 删除该配对的所有消息
    @Query("DELETE FROM private_chat_messages WHERE pairId = :pairId")
    suspend fun deleteByPairId(pairId: String)
}
