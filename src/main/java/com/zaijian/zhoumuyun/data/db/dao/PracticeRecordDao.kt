package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.PracticeRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * PracticeRecord DAO（P6 专长进化系统 · 蒸馏第1层）
 */
@Dao
interface PracticeRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PracticeRecordEntity)

    // ── 蒸馏触发判断：统计某专长下仍是 RAW 状态的记录数量 ──────

    @Query("""
        SELECT COUNT(*) FROM practice_records
        WHERE specialtyId = :specialtyId AND digestStatus = 'RAW'
    """)
    suspend fun countRawRecords(specialtyId: String): Int

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId AND digestStatus = 'RAW'
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getOldestRawRecords(specialtyId: String, limit: Int): List<PracticeRecordEntity>

    // ── 蒸馏执行：批量降级为 DIGESTED，原文已转存归档表后调用 ──
    // 注意：CONFLICTING 类型不在此调用范围内，由调用方（DistillationEngine）
    // 在挑选待降级 id 列表时提前过滤掉，本 Dao 方法本身不做业务判断。

    @Query("""
        UPDATE practice_records
        SET digestStatus = 'DIGESTED', digestedIntoId = :digestId, content = :placeholder
        WHERE id = :recordId
    """)
    suspend fun markDigested(recordId: String, digestId: String, placeholder: String)

    // ── 里程碑标记（用户在专长档案页手动操作）──────────────────

    @Query("UPDATE practice_records SET digestStatus = 'MILESTONE' WHERE id = :recordId")
    suspend fun markMilestone(recordId: String)

    // ── 读取：专长档案页"修炼历程"列表（按时间倒序，含所有状态） ──

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId
        ORDER BY createdAt DESC
    """)
    fun observeAllForSpecialty(specialtyId: String): Flow<List<PracticeRecordEntity>>

    @Query("SELECT * FROM practice_records WHERE id = :recordId LIMIT 1")
    suspend fun getById(recordId: String): PracticeRecordEntity?

    @Query("""
        SELECT * FROM practice_records
        WHERE specialtyId = :specialtyId AND comparisonResult = 'CONFLICTING'
          AND digestStatus = 'RAW'
        ORDER BY createdAt DESC
    """)
    suspend fun getUnresolvedConflicts(specialtyId: String): List<PracticeRecordEntity>
}
