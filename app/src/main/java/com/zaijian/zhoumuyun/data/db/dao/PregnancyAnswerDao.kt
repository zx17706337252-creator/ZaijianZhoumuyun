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

    // P0-1 修复：insert() 返回 Long 以检测 IGNORE 失败。
    // Room 的 @Insert(onConflict = IGNORE) 返回 -1 时表示冲突未插入。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: PregnancyAnswerEntity): Long

    /**
     * P1-6-9 修复核心：原子化"检查槽位锁定状态→插入答案→返回历史记录"。
     * @Transaction 确保整个操作在单个 SQLite 事务内完成，消除 TOCTOU 竞态。
     * LLM 一致性判定（耗时 IO）在事务外由 Repository 层完成，不阻塞事务。
     *
     * P0-1 修复：改为检测 insert() 返回值，IGNORE 失败时返回 -1，
     * 替代原先仅靠 isSlotLocked 前检查（存在 TOCTOU 窗口）。
     *
     * P1-6 修复：返回历史记录列表（事务内快照），供 Repository 层做一致性判定。
     * 原先 getBySlot 在事务外调用，insert 和 getBySlot 之间可能被并发插入，
     * 导致历史列表与 count 不一致。
     *
     * @return Triple<Boolean, Int, List<PregnancyAnswerEntity>>：
     *   first = 本次是否成功插入，second = 插入后总数，third = 事务内历史快照
     */
    @Transaction
    open suspend fun recordIfOpen(entity: PregnancyAnswerEntity): Triple<Boolean, Int, List<PregnancyAnswerEntity>> {
        val locked = isSlotLocked(entity.motherCharacterId, entity.questionType, entity.slotIndex)
        if (locked) return Triple(false, 0, emptyList())
        val inserted = insert(entity)
        if (inserted == -1L) return Triple(false, 0, emptyList())  // IGNORE 冲突
        val count = countBySlot(entity.motherCharacterId, entity.questionType, entity.slotIndex)
        val history = getBySlot(entity.motherCharacterId, entity.questionType, entity.slotIndex)
        return Triple(true, count, history)
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
     * 问题13修复：一次查询返回该母亲角色所有「已锁定」槽位的 (questionType, slotIndex)。
     * 供 [PregnancyAnswerRepository.nextUnlockedSlot] / [isAllSlotsLocked] 使用，
     * 将原先对 6 个槽位逐个调用 isSlotLocked()（最多 6 次查询）合并为 1 次查询。
     */
    @Query("""
        SELECT DISTINCT questionType, slotIndex FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId AND isLocked = 1
    """)
    abstract suspend fun getLockedSlotKeys(motherCharacterId: Int): List<LockedSlotKey>

    /**
     * 问题13修复：某槽位最近一条答案（按 answeredAt 倒序取首条）。
     * lockSlot() 会把该槽位所有历史行统一置为 isLocked=1，因此最新一行的
     * isLocked 字段即可代表整个槽位的锁定状态，用于 [PregnancyAnswerRepository.getLockedAnswer]
     * 将原先 getBySlot() + isSlotLocked() 两次查询合并为 1 次查询。
     */
    @Query("""
        SELECT * FROM pregnancy_answers
        WHERE motherCharacterId = :motherCharacterId
          AND questionType = :questionType
          AND slotIndex = :slotIndex
        ORDER BY answeredAt DESC
        LIMIT 1
    """)
    abstract suspend fun getLatestBySlot(
        motherCharacterId: Int,
        questionType: String,
        slotIndex: Int,
    ): PregnancyAnswerEntity?

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

/**
 * 问题13修复：[PregnancyAnswerDao.getLockedSlotKeys] 的投影结果，
 * 字段名需与 pregnancy_answers 表的列名一致，供 Room 自动映射 DISTINCT 查询结果。
 */
data class LockedSlotKey(
    val questionType: String,
    val slotIndex: Int,
)
