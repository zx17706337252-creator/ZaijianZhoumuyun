package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.RoundtableMessageDao
import com.zaijian.zhoumuyun.data.db.entity.RoundtableMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 圆桌消息 Repository（Phase 3 修复手册第3条）。
 *
 * 包装 [RoundtableMessageDao]，逐方法透传。与 [MessageRepository] 分开
 * 成两个类而不是合并——两者对应不同表（`roundtable_messages` vs
 * `messages`）和不同实体，合并到一个类里会让方法集合混杂不清晰。
 */
class RoundtableMessageRepository(private val dao: RoundtableMessageDao) {

    suspend fun insert(message: RoundtableMessageEntity) = dao.insert(message)

    suspend fun getByRoundtable(roundtableId: String): List<RoundtableMessageEntity> =
        dao.getByRoundtable(roundtableId)

    suspend fun getByRoundtablePaged(
        roundtableId: String,
        limit: Int,
        offset: Int,
    ): List<RoundtableMessageEntity> = dao.getByRoundtablePaged(roundtableId, limit, offset)

    fun observeByRoundtable(roundtableId: String): Flow<List<RoundtableMessageEntity>> =
        dao.observeByRoundtable(roundtableId)

    suspend fun deleteByRoundtable(roundtableId: String) = dao.deleteByRoundtable(roundtableId)

    suspend fun countByRoundtable(roundtableId: String): Int = dao.countByRoundtable(roundtableId)

    suspend fun findMostRecentRoundtableIdForSpeaker(characterId: String): String? =
        dao.findMostRecentRoundtableIdForSpeaker(characterId)
}
