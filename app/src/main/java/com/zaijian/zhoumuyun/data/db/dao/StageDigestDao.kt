package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.StageDigestEntity
import kotlinx.coroutines.flow.Flow

/**
 * StageDigest DAO（P6 专长进化系统 · 蒸馏第2层）
 */
@Dao
interface StageDigestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(digest: StageDigestEntity)

    // ── 蒸馏触发判断：统计未并入 styleNotes 的阶段摘要数量 ──────

    @Query("""
        SELECT COUNT(*) FROM stage_digests
        WHERE specialtyId = :specialtyId AND mergedIntoProfile = 0
    """)
    suspend fun countUnmerged(specialtyId: String): Int

    @Query("""
        SELECT * FROM stage_digests
        WHERE specialtyId = :specialtyId AND mergedIntoProfile = 0
        ORDER BY createdAt ASC
    """)
    suspend fun getUnmerged(specialtyId: String): List<StageDigestEntity>

    // ── 合并执行：标记已并入，记录本身不删除（历史可追溯）────

    @Query("UPDATE stage_digests SET mergedIntoProfile = 1 WHERE id = :digestId")
    suspend fun markMerged(digestId: String)

    @Query("""
        UPDATE stage_digests SET mergedIntoProfile = 1
        WHERE id IN (:digestIds)
    """)
    suspend fun markMergedBatch(digestIds: List<String>)

    // ── 读取：专长档案页"修炼历程"分组标题展示用 ────────────────

    @Query("""
        SELECT * FROM stage_digests
        WHERE specialtyId = :specialtyId
        ORDER BY periodStart DESC
    """)
    fun observeAllForSpecialty(specialtyId: String): Flow<List<StageDigestEntity>>
}
