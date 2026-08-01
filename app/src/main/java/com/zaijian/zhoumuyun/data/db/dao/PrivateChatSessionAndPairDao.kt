package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity

/**
 * 跨表原子事务 DAO（方案_角色间私聊_v2-5 4 节，v2.3 新增；v2.7 新增 markDisconnectedAtomic）
 *
 * completeSessionAtomic() 将 session 状态 → completed 和 pair 计数 +1 合并到
 * 同一个 @Transaction 内。如果 Worker 在消息全部写完、但这一步执行前被系统
 * 杀掉，消息已存在但计数没加一——改为原子方法后要么都更新、要么都不更新，
 * 保证 maxSessionsPerDay 硬上限真正生效（见 6 节 v2.3 补充说明）。
 *
 * markDisconnectedAtomic()（v2.7 新增，方案 v1.5 6.4 节）与 completeSessionAtomic
 * 平行：角色主动下线导致会话结束时，session 状态 → disconnected（而非 completed），
 * 但同样需要 pair 计数 +1——这次对话仍然真实占用了一次"今日次数"，不能因为
 * 是被角色中断的就不计数，否则用户可以靠不断惹角色生气触发下线来无限刷
 * 次数、绕过 maxSessionsPerDay 每日上限。两个字段的更新同样需要原子性，
 * 理由与 completeSessionAtomic 相同。
 *
 * 采用 abstract class 而非 interface，是 Room 的要求：带 @Transaction 方法体
 * 的 DAO 必须是 abstract class（项目里 MemoryTagDao 同款写法）。
 */
@Dao
abstract class PrivateChatSessionAndPairDao {

    @Query("UPDATE private_chat_sessions SET status = 'completed', turnCount = :turnCount WHERE sessionId = :sessionId")
    abstract suspend fun markCompleted(sessionId: String, turnCount: Int)

    @Query("UPDATE private_chat_sessions SET status = 'disconnected', turnCount = :turnCount WHERE sessionId = :sessionId")
    abstract suspend fun markDisconnected(sessionId: String, turnCount: Int)

    @Query("UPDATE private_chat_pairs SET sessionsUsedToday = sessionsUsedToday + 1, lastSessionAt = :lastSessionAt WHERE pairId = :pairId")
    abstract suspend fun incrementSessionsUsed(pairId: String, lastSessionAt: Long)

    @Transaction
    open suspend fun completeSessionAtomic(sessionId: String, pairId: String, turnCount: Int) {
        markCompleted(sessionId, turnCount)
        incrementSessionsUsed(pairId, System.currentTimeMillis())
    }

    @Transaction
    open suspend fun markDisconnectedAtomic(sessionId: String, pairId: String, turnCount: Int) {
        markDisconnected(sessionId, turnCount)
        incrementSessionsUsed(pairId, System.currentTimeMillis())
    }
}
