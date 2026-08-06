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

    // 修复 #5：retry 重跑续接旧 interrupted session 时，把状态改回 in_progress
    // （不 insert 新行，同一 sessionId 主键复用同一条记录）。errorMessage 清空——
    // 这次是"正在续接"，不再是"上次失败"的状态，避免 UI/导出把已清空的错误
    // 误当成本轮仍然有效的错误信息展示。
    @Query("UPDATE private_chat_sessions SET status = 'in_progress', errorMessage = NULL WHERE sessionId = :sessionId")
    suspend fun markResumed(sessionId: String)

    // v2.7：markCompleted 已删除（死代码）。唯一调用方 PrivateChatSessionRepository.markCompleted
    // 已一并删除。真正生效的落库路径是 PrivateChatSessionAndPairDao 自己定义的
    // markCompleted/markDisconnected，走 @Transaction 原子方法，不经过这个接口。

    @Query("SELECT * FROM private_chat_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): PrivateChatSessionEntity?

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    suspend fun getAllByPair(pairId: String): List<PrivateChatSessionEntity>

    @Query("SELECT * FROM private_chat_sessions WHERE pairId = :pairId ORDER BY startedAt ASC")
    fun observeByPair(pairId: String): Flow<List<PrivateChatSessionEntity>>

    // 修复：按角色查询近期私聊会话（join pairs 表），用于主对话 prompt 注入。
    // 让角色 A 在主对话中知道"我最近和 B 私聊过"，从而能主动调用
    // private_chat_history 查询详细内容，而非回答"对方没回复"。
    //
    // 私聊实时同步修复后：此方法不再是播报主路径（见 getUnnotifiedByCharacter），
    // 仅保留供其他仍需要"近期私聊"语义（而非"未告知"语义）的调用方使用。
    @Query(
        "SELECT s.* FROM private_chat_sessions s " +
            "INNER JOIN private_chat_pairs p ON s.pairId = p.pairId " +
            "WHERE (p.characterIdA = :characterId OR p.characterIdB = :characterId) " +
            "AND s.startedAt >= :sinceTimestamp " +
            "ORDER BY s.startedAt DESC LIMIT :limit"
    )
    suspend fun getRecentByCharacter(
        characterId: Int,
        sinceTimestamp: Long,
        limit: Int = 5,
    ): List<PrivateChatSessionEntity>

    /**
     * 私聊实时同步修复：按"未告知"查询该角色参与、但尚未在其主对话里播报过的私聊会话
     * （join pairs 表判断参与关系，用 SQLite 字符串匹配判断 notifiedCharacterIds 是否
     * 已包含该 characterId）。
     *
     * 不依赖时间窗口——只要还没告知过，不管过了多久都会被查到，直到调用方播报后
     * 调用 [markNotified] 把自己的 id 追加进去。
     *
     * status 排除 'in_progress'：会话还在进行中时不应该播报半截内容（此时逐字记录
     * 还不完整），等 runSession() 真正跑完变成 completed/interrupted/disconnected
     * 后才查得到。
     *
     * notifiedCharacterIds 用 ',' + id + ',' 包裹后做 LIKE 匹配，避免"1" 误命中
     * "12"/"21" 这类子串问题——存储格式约定为首尾都带逗号（见 [markNotified]）。
     */
    @Query(
        "SELECT s.* FROM private_chat_sessions s " +
            "INNER JOIN private_chat_pairs p ON s.pairId = p.pairId " +
            "WHERE (p.characterIdA = :characterId OR p.characterIdB = :characterId) " +
            "AND s.status != 'in_progress' " +
            "AND (',' || s.notifiedCharacterIds || ',') NOT LIKE ('%,' || :characterId || ',%') " +
            "ORDER BY s.startedAt ASC LIMIT :limit"
    )
    suspend fun getUnnotifiedByCharacter(
        characterId: Int,
        limit: Int = 5,
    ): List<PrivateChatSessionEntity>

    /**
     * 私聊实时同步修复：把 characterId 追加进 notifiedCharacterIds 列表，标记
     * "已经在这个角色的主对话里播报过这次会话"。
     *
     * 用 CASE 表达式在 SQL 层做"已存在则跳过、不存在则追加"的幂等判断，避免
     * 并发/重复调用时把同一个 id 拼接进去多次（虽然 LIKE 匹配本身对重复 id
     * 不敏感，但存储层保持干净便于人工排查）。追加时补前导逗号，与
     * [getUnnotifiedByCharacter] 的 ',' + ... + ',' 包裹匹配格式对齐。
     */
    @Query(
        "UPDATE private_chat_sessions SET notifiedCharacterIds = " +
            "CASE WHEN (',' || notifiedCharacterIds || ',') LIKE ('%,' || :characterId || ',%') " +
            "THEN notifiedCharacterIds " +
            "ELSE (CASE WHEN notifiedCharacterIds = '' THEN CAST(:characterId AS TEXT) " +
            "ELSE notifiedCharacterIds || ',' || CAST(:characterId AS TEXT) END) END " +
            "WHERE sessionId = :sessionId"
    )
    suspend fun markNotified(sessionId: String, characterId: Int)

    // A10-5 修复：级联删除——按 pairId 删除该配对的所有会话
    @Query("DELETE FROM private_chat_sessions WHERE pairId = :pairId")
    suspend fun deleteByPairId(pairId: String)
}
