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

    @Query("UPDATE private_chat_sessions SET status = 'completed', turnCount = :turnCount WHERE sessionId = :sessionId")
    suspend fun markCompleted(sessionId: String, turnCount: Int)

    @Query("SELECT * FROM private_chat_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): PrivateChatSessionEntity?

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity>

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>>
}
