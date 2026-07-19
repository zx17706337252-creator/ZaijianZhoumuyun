package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.EvolutionPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * EvolutionPlan DAO（P6 专长进化系统）
 *
 * 与 AgentPlanDao 的关键区别：archiveActive 之后旧记录依然完整保留、
 * 可查（getHistory 不区分 isActive），专长档案页需要展示方案演变的
 * 完整链条，不是只看"当前生效"那一条。
 */
@Dao
interface EvolutionPlanDao {

    // ── 写入 ──────────────────────────────────────────────────

    // P1-6-1 修复补充：改为 IGNORE，配合 (specialtyId, version) 唯一索引。
    // 事务内正常路径不会产生重复，IGNORE 仅作为最终兜底防止脏写入静默覆盖已有版本。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(plan: EvolutionPlanEntity)

    // ── 归档旧方案（写入新版本前调用，旧记录不删除、不清空内容）──

    @Query("""
        UPDATE evolution_plans
        SET isActive = 0
        WHERE specialtyId = :specialtyId AND isActive = 1
    """)
    suspend fun archiveActive(specialtyId: String)

    // ── 读取：当前生效方案 ────────────────────────────────────

    @Query("""
        SELECT * FROM evolution_plans
        WHERE specialtyId = :specialtyId AND isActive = 1
        LIMIT 1
    """)
    suspend fun getActivePlan(specialtyId: String): EvolutionPlanEntity?

    @Query("""
        SELECT * FROM evolution_plans
        WHERE specialtyId = :specialtyId AND isActive = 1
        LIMIT 1
    """)
    fun observeActivePlan(specialtyId: String): Flow<EvolutionPlanEntity?>

    // ── 读取：完整版本历史（专长档案页"方案随时间演变"展示用）──

    @Query("""
        SELECT * FROM evolution_plans
        WHERE specialtyId = :specialtyId
        ORDER BY version DESC
    """)
    suspend fun getAllVersions(specialtyId: String): List<EvolutionPlanEntity>

    @Query("""
        SELECT * FROM evolution_plans
        WHERE specialtyId = :specialtyId
        ORDER BY version DESC
    """)
    fun observeAllVersions(specialtyId: String): Flow<List<EvolutionPlanEntity>>

    @Query("""
        SELECT COALESCE(MAX(version), 0) FROM evolution_plans
        WHERE specialtyId = :specialtyId
    """)
    suspend fun getLatestVersionNumber(specialtyId: String): Int

    // ── 删除（专长档案被整体删除时级联清理，由 Repository 层调用） ──

    @Query("DELETE FROM evolution_plans WHERE specialtyId = :specialtyId")
    suspend fun deleteAllForSpecialty(specialtyId: String)
}
