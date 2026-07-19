package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * PracticeRecord DAO（P6 专长进化系统 · 蒸馏第1层）
 *
 * W1-004 修复：interface 改为 abstract class，以便添加带 @Transaction 的
 * 组合方法（[snapshotOldestRawRecordsIfThresholdMet]），将"判断是否达到蒸馏
 * 阈值 → 取出待蒸馏记录"的 count→get 两步合并为单个事务内的一致性快照读取，
 * 消除窗口审查报告 W1-004 指出的 TOCTOU 竞态（另一协程在 count 之后、get 之前
 * 插入/修改同一批 RAW 记录导致的重复蒸馏风险）。
 */
@Dao
abstract class PracticeRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(record: PracticeRecordEntity)

    // ── 蒸馏触发判断：统计某专长下仍是 RAW 状态的记录数量 ──────

    @Query("""
        SELECT COUNT(*) FROM practice_records
        WHERE specialtyId = :specialtyId AND digestStatus = 'RAW'
    """)
    abstract suspend fun countRawRecords(specialtyId: String): Int

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId AND digestStatus = 'RAW'
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    abstract suspend fun getOldestRawRecords(specialtyId: String, limit: Int): List<PracticeRecordEntity>

    /**
     * W1-004 修复核心：原子化"判断 RAW 记录数是否达到阈值 → 若达到则取出最旧的
     * 待蒸馏记录快照"。@Transaction 确保 count 与 get 在同一个 SQLite 事务内
     * 完成，读到的是同一个一致性快照，不会出现 count 通过阈值检查后、get 之前
     * 记录集合已被另一协程改变的情况。
     *
     * 未达阈值时返回空列表（调用方按"本轮无需蒸馏"处理，语义等价于原
     * `if (countRawRecords(...) >= threshold) getOldestRawRecords(...)`）。
     *
     * 注意：此方法只负责一致性读取，不做"认领"（不改写 digestStatus）。
     * 真正的写入仍由调用方在 [markDigested] 循环外层的 `db.withTransaction`
     * 中完成（见 DistillationTrigger.runRawToDigest），因为中间夹着不可回滚的
     * LLM 调用，不能把网络请求也纳入同一个 DB 事务。该窗口内的重复蒸馏风险
     * 由 DistillationTrigger 的按 specialtyId 分片 Mutex（[DistillationTrigger.getMutex]）
     * 兜底：同一 specialtyId 不会有两个 checkAndRunInternal 并发执行。
     */
    @Transaction
    open suspend fun snapshotOldestRawRecordsIfThresholdMet(
        specialtyId: String,
        threshold: Int,
        limit: Int,
    ): List<PracticeRecordEntity> {
        val rawCount = countRawRecords(specialtyId)
        if (rawCount < threshold) return emptyList()
        return getOldestRawRecords(specialtyId, limit)
    }

    // ── 蒸馏执行：批量降级为 DIGESTED，原文已转存归档表后调用 ──
    // 注意：CONFLICTING 类型不在此调用范围内，由调用方（DistillationEngine）
    // 在挑选待降级 id 列表时提前过滤掉，本 Dao 方法本身不做业务判断。

    @Query("""
        UPDATE practice_records
        SET digestStatus = 'DIGESTED', digestedIntoId = :digestId, content = :placeholder
        WHERE id = :recordId
    """)
    abstract suspend fun markDigested(recordId: String, digestId: String, placeholder: String)

    // ── 里程碑标记（用户在专长档案页手动操作）──────────────────

    @Query("UPDATE practice_records SET digestStatus = 'MILESTONE' WHERE id = :recordId")
    abstract suspend fun markMilestone(recordId: String)

    // ── 读取：专长档案页"修炼历程"列表（按时间倒序，含所有状态） ──

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId
        ORDER BY createdAt DESC
    """)
    abstract fun observeAllForSpecialty(specialtyId: String): Flow<List<PracticeRecordEntity>>

    @Query("SELECT * FROM practice_records WHERE id = :recordId LIMIT 1")
    abstract suspend fun getById(recordId: String): PracticeRecordEntity?

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId AND comparisonResult = 'CONFLICTING'
          AND digestStatus = 'RAW'
        ORDER BY createdAt DESC
    """)
    abstract suspend fun getUnresolvedConflicts(specialtyId: String): List<PracticeRecordEntity>

    // ── 方案 2-10：CONFLICTING 记录去重标记 ─────────────────────

    /**
     * 将某专长下所有 CONFLICTING + 未标记的记录标记为 CONFLICTING_PENDING_USER，
     * 避免被 countRawRecords 反复计入导致空蒸馏触发。
     */
    @Query("""
        UPDATE practice_records
        SET digestStatus = 'CONFLICTING_PENDING_USER'
        WHERE specialtyId = :specialtyId
          AND comparisonResult = 'CONFLICTING'
          AND (digestStatus IS NULL OR digestStatus = '' OR digestStatus = 'RAW')
    """)
    abstract suspend fun markConflictingPendingUser(specialtyId: String)

    // ── W1-002 修复：圆桌播报补偿 ────────────────────────────
    // 落库与圆桌播报之间跨表且含不可回滚的 LLM 调用，无法用事务整体包裹，
    // 改用"先落 PENDING、播报成功后落 COMPLETED、下次运行扫描补发"的
    // 最终一致性模式。

    /** 查询所有跨进程重启后仍未成功播报到圆桌的记录（PENDING），全库范围，
     *  不限定某个 specialtyId——补偿本身与当天要修炼哪个专长无关。 */
    @Query("SELECT * FROM practice_records WHERE roundtablePosted = 0 ORDER BY createdAt ASC")
    abstract suspend fun getUnpostedRecords(): List<PracticeRecordEntity>

    @Query("UPDATE practice_records SET roundtablePosted = 1 WHERE id = :recordId")
    abstract suspend fun markRoundtablePosted(recordId: String)
}
