package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.SpecialtyProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * SpecialtyProfile DAO（P6 专长进化系统 · 蒸馏第3层：核心风格档案）
 *
 * maturityStage 的阈值（5/15次）属于可调配置常量（见 SpecialtyEvolutionConfig），
 * 不写死在 SQL 里——本 Dao 只提供原子递增和单独的字段更新方法，
 * "次数是否达到了该升级成熟度" 的判断逻辑放在调用方（DailyPracticeWorker /
 * DistillationEngine），避免阈值改动需要同时改 SQL 和 Kotlin 两处。
 */
@Dao
interface SpecialtyProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: SpecialtyProfileEntity)

    @Query("SELECT * FROM specialty_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SpecialtyProfileEntity?

    @Query("SELECT * FROM specialty_profiles WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<SpecialtyProfileEntity?>

    @Query("SELECT * FROM specialty_profiles WHERE characterId = :characterId ORDER BY createdAt ASC")
    fun observeAllForCharacter(characterId: Int): Flow<List<SpecialtyProfileEntity>>

    /** DailyPracticeWorker 每次调度时取所有生效中的专长（跨角色），逐一执行修炼 */
    @Query("SELECT * FROM specialty_profiles WHERE isActive = 1")
    suspend fun getAllActiveProfiles(): List<SpecialtyProfileEntity>

    /**
     * 通过 characterId + domain 反查专长档案（竞赛奖惩反哺路径需要：
     * 已知 characterId + projectDomain → 取 specialtyId → 写 SystemSuggestionEntity）。
     */
    @Query("SELECT * FROM specialty_profiles WHERE characterId = :characterId AND domain = :domain LIMIT 1")
    suspend fun getByCharacterAndDomain(characterId: Int, domain: String): SpecialtyProfileEntity?

    // ── 修炼次数与时间戳 ──────────────────────────────────────

    @Query("""
        UPDATE specialty_profiles
        SET practiceCount = practiceCount + 1, lastPracticeAt = :timestamp, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementPracticeCount(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE specialty_profiles SET maturityStage = :stage, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateMaturityStage(id: String, stage: String, timestamp: Long = System.currentTimeMillis())

    // ── 候选观察池 ────────────────────────────────────────────

    @Query("""
        UPDATE specialty_profiles
        SET candidateObservationsJson = :json, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateCandidateObservations(id: String, json: String, timestamp: Long = System.currentTimeMillis())

    // ── 风格说明书（蒸馏第2→3层合并时整段覆盖写）──────────────

    @Query("""
        UPDATE specialty_profiles
        SET styleNotes = :styleNotes, lastDigestAt = :timestamp, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateStyleNotes(id: String, styleNotes: String, timestamp: Long = System.currentTimeMillis())

    // ── 风格分歧标记 ──────────────────────────────────────────

    @Query("""
        UPDATE specialty_profiles
        SET hasUnresolvedConflict = :hasConflict, unresolvedConflictDescription = :description, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateConflictState(
        id: String,
        hasConflict: Boolean,
        description: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    // ── 用户确认互动标记（晋升判定条件3）────────────────────────

    @Query("UPDATE specialty_profiles SET hasUserConfirmedAtLeastOnce = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun markUserConfirmed(id: String, timestamp: Long = System.currentTimeMillis())

    // ── 晋升 Identity Layer ──────────────────────────────────

    @Query("""
        UPDATE specialty_profiles
        SET promotedToIdentity = 1, styleNotes = :remainingStyleNotes, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun markPromoted(id: String, remainingStyleNotes: String, timestamp: Long = System.currentTimeMillis())

    // ── 启用/停用 ─────────────────────────────────────────────

    @Query("UPDATE specialty_profiles SET isActive = :active, updatedAt = :timestamp WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM specialty_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
