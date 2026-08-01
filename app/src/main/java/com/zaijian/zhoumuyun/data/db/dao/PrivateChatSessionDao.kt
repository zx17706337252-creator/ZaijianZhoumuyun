package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivateChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: PrivateChatSessionEntity)

    @Query("UPDATE private_chat_sessions SET status = 'interrupted', turnCount = :turnCount, errorMessage = :errorMessage WHERE sessionId = :sessionId")
    suspend fun markInterrupted(sessionId: String, turnCount: Int, errorMessage: String)

    // v2.7：markCompleted 已删除（死代码）。唯一调用方 PrivateChatSessionRepository.markCompleted
    // 已一并删除。真正生效的落库路径是 PrivateChatSessionAndPairDao 自己定义的
    // markCompleted/markDisconnected，走 @Transaction 原子方法，不经过这个接口。

    @Query("SELECT * FROM private_chat_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): PrivateChatSessionEntity?

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity>

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>>

    // A10-5 修复：级联删除——按 pairId 删除该配对的所有会话
    @Query("DELETE FROM private_chat_sessions WHERE pairId = :pairId")
    suspend fun deleteByPairId(pairId: String)
}
