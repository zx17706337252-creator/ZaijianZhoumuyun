package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.PrivateChatPairDao
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/**
 * 私聊配对 Repository（方案_角色间私聊_v2-5 3.1 节）
 *
 * 薄封装，逐方法透传 DAO，与 RoundtableMessageRepository 风格一致。
 */
class PrivateChatPairRepository(private val dao: PrivateChatPairDao) {

    suspend fun insert(pair: PrivateChatPairEntity) = dao.insert(pair)

    suspend fun upsert(pair: PrivateChatPairEntity) = dao.upsert(pair)

    suspend fun get(pairId: String): PrivateChatPairEntity? = dao.get(pairId)

    fun observe(pairId: String): Flow<PrivateChatPairEntity?> = dao.observe(pairId)

    suspend fun getAll(): List<PrivateChatPairEntity> = dao.getAll()

    fun observeAll(): Flow<List<PrivateChatPairEntity>> = dao.observeAll()

    fun observeEnabled(): Flow<List<PrivateChatPairEntity>> = dao.observeEnabled()

    suspend fun getByCharacters(idA: Int, idB: Int): PrivateChatPairEntity? = dao.getByCharacters(idA, idB)

    suspend fun updateEnabled(pairId: String, enabled: Boolean) = dao.updateEnabled(pairId, enabled)

    suspend fun updateParams(pairId: String, maxTurns: Int, maxSessions: Int, cooldown: Int) =
        dao.updateParams(pairId, maxTurns, maxSessions, cooldown)

    suspend fun resetDailyCounter(pairId: String, resetAt: Long) = dao.resetDailyCounter(pairId, resetAt)

    // 角色忠诚锁定·角色自主下线状态（方案 v1.5 第 6.4 节）
    suspend fun updateCharacterDisconnectState(pairId: String, state: String) =
        dao.updateCharacterDisconnectState(pairId, state)

    companion object {
        /**
         * 生成规范化 pairId：两个 characterId 按数值排序后拼接，如 "1_7"。
         * 保证 (3, 7) 和 (7, 3) 生成同一个 pairId。
         */
        fun generatePairId(charIdA: Int, charIdB: Int): String {
            val (min, max) = if (charIdA <= charIdB) charIdA to charIdB else charIdB to charIdA
            return "${min}_${max}"
        }

        /**
         * 判断给定的 timestamp 是否属于"今天"（与 usedTodayResetAt 比较）。
         * 如果 usedTodayResetAt 早于今天零点，说明需要重置计数。
         *
         * 验收修复：原写法 `(now / 86_400_000L) * 86_400_000L` 是把时间戳按
         * UTC 对齐到零点，不是设备本地时区的零点——对 UTC+8 的用户来说，
         * 每天的重置点会早 8 小时触发（本地时间早上 8 点前后这个"今天"的
         * 计数是否清零会因为按 UTC 算而变得不稳定）。改用 Calendar 按
         * 设备默认时区取当天零点，重置点与用户实际感知的"今天"一致。
         */
        fun isStaleDay(usedTodayResetAt: Long, now: Long = System.currentTimeMillis()): Boolean {
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return usedTodayResetAt < todayStart
        }
    }
}
