package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivateChatPairDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: PrivateChatPairEntity)

    @Upsert
    suspend fun upsert(pair: PrivateChatPairEntity)

    @Query("SELECT * FROM private_chat_pairs WHERE pairId = :pairId")
    suspend fun get(pairId: String): PrivateChatPairEntity?

    @Query("SELECT * FROM private_chat_pairs WHERE pairId = :pairId")
    fun observe(pairId: String): Flow<PrivateChatPairEntity?>

    @Query("SELECT * FROM private_chat_pairs ORDER BY characterIdA ASC, characterIdB ASC")
    suspend fun getAll(): List<PrivateChatPairEntity>

    @Query("SELECT * FROM private_chat_pairs ORDER BY characterIdA ASC, characterIdB ASC")
    fun observeAll(): Flow<List<PrivateChatPairEntity>>

    @Query("SELECT * FROM private_chat_pairs WHERE enabled = 1 ORDER BY characterIdA ASC, characterIdB ASC")
    fun observeEnabled(): Flow<List<PrivateChatPairEntity>>

    @Query(
        "SELECT * FROM private_chat_pairs WHERE " +
            "(characterIdA = :idA AND characterIdB = :idB) OR " +
            "(characterIdA = :idB AND characterIdB = :idA)"
    )
    suspend fun getByCharacters(idA: Int, idB: Int): PrivateChatPairEntity?

    @Query("UPDATE private_chat_pairs SET enabled = :enabled WHERE pairId = :pairId")
    suspend fun updateEnabled(pairId: String, enabled: Boolean)

    @Query("UPDATE private_chat_pairs SET maxTurnsPerSession = :maxTurns, maxSessionsPerDay = :maxSessions, cooldownMinutes = :cooldown WHERE pairId = :pairId")
    suspend fun updateParams(pairId: String, maxTurns: Int, maxSessions: Int, cooldown: Int)

    @Query("UPDATE private_chat_pairs SET sessionsUsedToday = 0, usedTodayResetAt = :resetAt WHERE pairId = :pairId")
    suspend fun resetDailyCounter(pairId: String, resetAt: Long)

    @Query("UPDATE private_chat_pairs SET sessionsUsedToday = sessionsUsedToday + 1, lastSessionAt = :lastSessionAt WHERE pairId = :pairId")
    suspend fun incrementSessionsUsed(pairId: String, lastSessionAt: Long)

    // 角色忠诚锁定·角色自主下线状态（方案 v1.5 第 6.4 节）
    @Query("UPDATE private_chat_pairs SET characterDisconnectState = :state WHERE pairId = :pairId")
    suspend fun updateCharacterDisconnectState(pairId: String, state: String)

    // A10-5 修复：私聊配对删除——仅删 pairs 表记录，
    // 消息/会话表的级联删除由 ViewModel 层 withTransaction 统一处理。
    @Query("DELETE FROM private_chat_pairs WHERE pairId = :pairId")
    suspend fun deleteByPairId(pairId: String)
}
