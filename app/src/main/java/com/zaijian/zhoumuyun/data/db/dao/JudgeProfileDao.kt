package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.JudgeProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * JudgeProfile DAO（裁判与竞争机制 · 裁判档案）
 *
 * 裁判档案由 CompetitionRoundManager 懒创建：
 * 角色第一次被指定为裁判且该方向无档案时，插入一条空白档案
 * （standardNotes="" / maturityStage="EXPLORING"），让她先按自身审美评。
 *
 * 候选修正池、standardNotes 整段覆盖写、成熟度更新等业务逻辑
 * 均在调用方处理，Dao 只提供原子读写。
 */
@Dao
interface JudgeProfileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: JudgeProfileEntity)

    @Query("SELECT * FROM judge_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): JudgeProfileEntity?

    @Query("SELECT * FROM judge_profiles WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<JudgeProfileEntity?>

    @Query("SELECT * FROM judge_profiles WHERE characterId = :characterId ORDER BY createdAt ASC")
    fun observeAllForCharacter(characterId: Int): Flow<List<JudgeProfileEntity>>

    /** 查找某角色在某方向的裁判档案（懒创建前的存在性检查） */
    @Query("SELECT * FROM judge_profiles WHERE characterId = :characterId AND domain = :domain LIMIT 1")
    suspend fun getByCharacterAndDomain(characterId: Int, domain: String): JudgeProfileEntity?

    /**
     * 懒创建的原子版本：查询+插入包在同一事务里，配合 insert 的 IGNORE 策略
     * 与 (characterId, domain) 唯一索引，彻底杜绝并发双击下的 TOCTOU 竞态。
     * 调用方（ensureJudgeProfile）应优先走这个方法，而不是分开调用 getByCharacterAndDomain + insert。
     */
    @Transaction
    suspend fun ensureProfile(profile: JudgeProfileEntity): JudgeProfileEntity {
        val existing = getByCharacterAndDomain(profile.characterId, profile.domain)
        if (existing != null) return existing
        insert(profile)
        // IGNORE 策略下，若并发场景已被其他事务插入，这里 insert 不会报错也不会生效，
        // 重新查一次拿到真正落库的那条记录。
        return getByCharacterAndDomain(profile.characterId, profile.domain) ?: profile
    }

    /** 取所有生效中的裁判档案（裁判成熟度连续检查用） */
    @Query("SELECT * FROM judge_profiles WHERE isActive = 1")
    suspend fun getAllActiveProfiles(): List<JudgeProfileEntity>

    // ── 裁判次数与时间戳 ──────────────────────────────────────

    @Query("""
        UPDATE judge_profiles
        SET judgeCount = judgeCount + 1, lastJudgedAt = :timestamp, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun incrementJudgeCount(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE judge_profiles SET maturityStage = :stage, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateMaturityStage(id: String, stage: String, timestamp: Long = System.currentTimeMillis())

    // ── 候选修正池 ────────────────────────────────────────────

    @Query("""
        UPDATE judge_profiles
        SET candidateCorrectionsJson = :json, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateCandidateCorrections(id: String, json: String, timestamp: Long = System.currentTimeMillis())

    // ── 评判标准说明书（整段覆盖写）──────────────────────────────

    @Query("""
        UPDATE judge_profiles
        SET standardNotes = :standardNotes, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateStandardNotes(id: String, standardNotes: String, timestamp: Long = System.currentTimeMillis())

    // ── 标准分歧标记 ──────────────────────────────────────────

    @Query("""
        UPDATE judge_profiles
        SET hasUnresolvedConflict = :hasConflict, unresolvedConflictDescription = :description, updatedAt = :timestamp
        WHERE id = :id
    """)
    suspend fun updateConflictState(
        id: String,
        hasConflict: Boolean,
        description: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    // ── 启用/停用 ─────────────────────────────────────────────

    @Query("UPDATE judge_profiles SET isActive = :active, updatedAt = :timestamp WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM judge_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
