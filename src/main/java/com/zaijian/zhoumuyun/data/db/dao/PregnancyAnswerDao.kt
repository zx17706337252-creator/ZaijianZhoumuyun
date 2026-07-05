package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.PregnancyAnswerEntity
import kotlinx.coroutines.flow.Flow

// P1-6-9 修复：将 DAO 由 interface 改为 abstract class，以便添加带 @Transaction 的具体方法。
// recordIfOpen() 将 isSlotLocked→insert→getBySlot→count 四步包裹为单个数据库事务，
// 消除 TOCTOU 竞态（并发两次 recordAnswer 均通过 isSlotLocked=false 检查后双写问题）。
@Dao
abstract class PregnancyAnswerDao {

    // P1-6-9 修复：改为 IGNORE，配合唯一索引 (motherCharacterId, questionType, slotIndex, answeredAt)。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: PregnancyAnswerEntity)

    /**
     * P1-6-9 修复核心：原子化"检查槽位锁定状态→插入答案→返回历史记录数"三步。
     * @Transaction 确保整个操作在单个 SQLite 事务内完成，消除 TOCTOU 竞态。
     * LLM 一致性判定（耗时 IO）在事务外由 Repository 层完成，不阻塞事务。
     *
     * @return Pair<Boolean, Int>：first = 本次是否成功插入（false 表示槽位已锁定），
     *                             second = 插入后该槽位的历史答案总数（0 表示未插入）
     */
    @Transaction
    open suspend fun recordIfOpen(entity: PregnancyAnswerEntity): Pair<Boolean, Int> {
        val locked = isSlotLocked(entity.motherCharacterId, entity.questionType, entity.slotIndex)
        if (locked) return false to 0
        insert(entity)
        val count = countBySlot(entity.motherCharacterId, entity.questionType, entity.slotIndex)
        return true to count
    }

    /** 某次孕期的全部问答，按时间顺序（D4 生成器读取用） */
    @Query("""
        SELECT * FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND pregnancyStartedAt = :pregnancyStartedAt
        ORDER BY answeredAt ASC
    """)
    abstract suspend fun getByPregnancy(
        motherCharacterId: Int,
        pregnancyStartedAt: Long,
    ): List<PregnancyAnswerEntity>

    /** 某次孕期的问答数量（判断共设是否已完成） */
    @Query("""
        SELECT COUNT(*) FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND pregnancyStartedAt = :pregnancyStartedAt
    """)
    abstract suspend fun countByPregnancy(motherCharacterId: Int, pregnancyStartedAt: Long): Int

    /** 某次孕期中是否已经问过某类型的问题（避免重复提问） */
    @Query("""
        SELECT COUNT(*) FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND pregnancyStartedAt = :pregnancyStartedAt
          AND questionType = :questionType
    """)
    abstract suspend fun hasQuestionType(
        motherCharacterId: Int,
        pregnancyStartedAt: Long,
        questionType: String,   // 传入 PregnancyQuestionType.name
    ): Int

    /** 某母亲所有孕期的问答档案，按时间倒序 */
    @Query("""
        SELECT * FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
        ORDER BY answeredAt DESC
    """)
    abstract fun observeAllByMother(motherCharacterId: Int): Flow<List<PregnancyAnswerEntity>>

    // ── D3 收敛链查询（v23→v24，按槽位维度，不分孕期）──────────────

    /**
     * 某槽位的收敛链全部历史答案，按时间顺序（用于语义一致性判定，
     * 比对对象始终是"最近一条答案"）。跨孕期累计，不按
     * pregnancyStartedAt 过滤。
     */
    @Query("""
        SELECT * FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND questionType = :questionType
          AND slotIndex = :slotIndex
        ORDER BY answeredAt ASC
    """)
    abstract suspend fun getBySlot(
        motherCharacterId: Int,
        questionType: String,
        slotIndex: Int,
    ): List<PregnancyAnswerEntity>

    /** 某槽位答案总数（recordIfOpen 内使用） */
    @Query("""
        SELECT COUNT(*) FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND questionType = :questionType
          AND slotIndex = :slotIndex
    """)
    abstract suspend fun countBySlot(
        motherCharacterId: Int,
        questionType: String,
        slotIndex: Int,
    ): Int

    /** 某槽位是否已锁定（锁定后 D3 提问逻辑不再触发该槽位） */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM pregnancy_answers
            WHERE motherCharacterId = :motherCharacterId
              AND questionType = :questionType
              AND slotIndex = :slotIndex
              AND isLocked = 1
        )
    """)
    abstract suspend fun isSlotLocked(
        motherCharacterId: Int,
        questionType: String,
        slotIndex: Int,
    ): Boolean

    /**
     * 锁定某槽位（语义一致或达到收敛上限第 3 次时调用）。
     * 该槽位的所有历史答案行统一标记为 isLocked = true。
     */
    @Query("""
        UPDATE pregnancy_answers
        SET isLocked = 1
        WHERE motherCharacterId = :motherCharacterId
          AND questionType = :questionType
          AND slotIndex = :slotIndex
    """)
    abstract suspend fun lockSlot(
        motherCharacterId: Int,
        questionType: String,
        slotIndex: Int,
    )
}
