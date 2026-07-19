package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity
import kotlinx.coroutines.flow.Flow

/**
 * StageDigest DAO（P6 专长进化系统 · 蒸馏第2层）
 *
 * W1-005 修复：interface 改为 abstract class，以便添加带 @Transaction 的
 * 组合方法（[snapshotUnmergedIfThresholdMet]），将"判断是否有足够未合并阶段
 * 摘要 → 取出未合并摘要"的 count→get 两步合并为单个事务内的一致性快照读取，
 * 消除窗口审查报告 W1-005 指出的与 W1-004 相同模式的 TOCTOU 竞态。
 */
@Dao
abstract class StageDigestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(digest: StageDigestEntity)

    // ── 蒸馏触发判断：统计未并入 styleNotes 的阶段摘要数量 ──────

    @Query("""
        SELECT COUNT(*) FROM stage_digests
        WHERE specialtyId = :specialtyId AND mergedIntoProfile = 0
    """)
    abstract suspend fun countUnmerged(specialtyId: String): Int

    @Query("""
        SELECT * FROM stage_digests
        WHERE specialtyId = :specialtyId AND mergedIntoProfile = 0
        ORDER BY createdAt ASC
    """)
    abstract suspend fun getUnmerged(specialtyId: String): List<StageDigestEntity>

    /**
     * W1-005 修复核心：原子化"判断未合并摘要数是否达到阈值 → 若达到则取出全部
     * 未合并摘要快照"。@Transaction 确保 count 与 get 在同一个 SQLite 事务内
     * 完成，避免 count 通过阈值检查后、get 之前摘要集合被另一协程改变（例如
     * 同一批摘要被并发的第1→2层蒸馏又插入了新摘要，或被另一次第2→3层合并
     * 提前标记 mergedIntoProfile）导致的重复合并风险。
     *
     * 未达阈值时返回空列表，语义等价于原
     * `if (countUnmerged(...) >= threshold) getUnmerged(...)`。
     *
     * 与 [PracticeRecordDao.snapshotOldestRawRecordsIfThresholdMet] 同理，
     * 真正的写入（markMergedBatch）仍在调用方 `db.withTransaction` 中完成
     * （LLM 合并调用不可回滚，不能纳入 DB 事务），该窗口由
     * DistillationTrigger 按 specialtyId 分片的 Mutex 兜底。
     */
    @Transaction
    open suspend fun snapshotUnmergedIfThresholdMet(
        specialtyId: String,
        threshold: Int,
    ): List<StageDigestEntity> {
        val unmergedCount = countUnmerged(specialtyId)
        if (unmergedCount < threshold) return emptyList()
        return getUnmerged(specialtyId)
    }

    // ── 合并执行：标记已并入，记录本身不删除（历史可追溯）────

    @Query("UPDATE stage_digests SET mergedIntoProfile = 1 WHERE id = :digestId")
    abstract suspend fun markMerged(digestId: String)

    @Query("""
        UPDATE stage_digests SET mergedIntoProfile = 1
        WHERE id IN (:digestIds)
    """)
    abstract suspend fun markMergedBatch(digestIds: List<String>)

    // ── 读取：专长档案页"修炼历程"分组标题展示用 ────────────────

    @Query("""
        SELECT * FROM stage_digests
        WHERE specialtyId = :specialtyId
        ORDER BY periodStart DESC
    """)
    abstract fun observeAllForSpecialty(specialtyId: String): Flow<List<StageDigestEntity>>

    // ── 方案 3-10：精确计数已合并的 digest 数量 ────────────────

    @Query("SELECT COUNT(*) FROM stage_digests WHERE specialtyId = :specialtyId AND mergedIntoProfile = 1")
    abstract suspend fun countMerged(specialtyId: String): Int

    // ── 删除（专长档案被整体删除时级联清理，由 Repository 层调用） ──
    // 审查报告问题15配套：deleteProfile() 注释称级联范围包含 stage_digests，
    // 但此前一直没有对应的删除方法，实现与注释不一致。

    @Query("DELETE FROM stage_digests WHERE specialtyId = :specialtyId")
    abstract suspend fun deleteAllForSpecialty(specialtyId: String)
}
