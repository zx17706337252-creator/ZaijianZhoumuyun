package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.CompetitionEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * CompetitionEntry DAO（裁判与竞争机制 · 参赛条目与评分记录）
 *
 * 评分字段分步写入：
 *   1. COLLECTING 阶段：insert（content 写入，评分字段均为 null/默认值）
 *   2. JUDGING 阶段：updateJudgeResult + updateSelfResult（各自独立写，盲评隔离）
 *   3. AWAITING_USER → COMPLETED：updateUserScore → updateCompositeScore
 */
@Dao
interface CompetitionEntryDao {

    // W1 修复：改为 IGNORE，配合 UNIQUE(roundId, characterId) 约束，
    // 在"先查后写"竞态场景下静默跳过重复插入，不覆盖已有 entry 数据。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: CompetitionEntryEntity)

    @Query("SELECT * FROM competition_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CompetitionEntryEntity?

    /** 取某轮次所有参赛条目（结果展示页 + finalizeRound 算分用） */
    @Query("SELECT * FROM competition_entries WHERE roundId = :roundId ORDER BY createdAt ASC")
    suspend fun getAllForRound(roundId: String): List<CompetitionEntryEntity>

    // 第8窗口问题2修复：批量取多个轮次的所有条目，供 BriefingRepository.generateBriefing()
    // 消除 N+1 查询——原先对 completedRounds 逐轮调用 getAllForRound()，
    // 改为一次 IN 查询后在内存按 roundId/characterId 分组。
    @Query("SELECT * FROM competition_entries WHERE roundId IN (:roundIds) ORDER BY createdAt ASC")
    suspend fun getAllForRounds(roundIds: List<String>): List<CompetitionEntryEntity>

    @Query("SELECT * FROM competition_entries WHERE roundId = :roundId ORDER BY createdAt ASC")
    fun observeAllForRound(roundId: String): Flow<List<CompetitionEntryEntity>>

    /** 取某角色在某轮次的条目（自评回写 + 奖惩反哺用） */
    @Query("SELECT * FROM competition_entries WHERE roundId = :roundId AND characterId = :characterId LIMIT 1")
    suspend fun getByRoundAndCharacter(roundId: String, characterId: Int): CompetitionEntryEntity?

    // ── 裁判评分（JUDGING 阶段）──────────────────────────────────

    @Query("""
        UPDATE competition_entries
        SET judgeScore = :score, judgeReasoning = :reasoning
        WHERE id = :id
    """)
    suspend fun updateJudgeResult(id: String, score: Int, reasoning: String)

    // ── 角色自评（JUDGING 阶段，盲评）────────────────────────────

    @Query("""
        UPDATE competition_entries
        SET selfScore = :score, selfReasoning = :reasoning
        WHERE id = :id
    """)
    suspend fun updateSelfResult(id: String, score: Int, reasoning: String)

    // ── 用户评分（AWAITING_USER 阶段）────────────────────────────

    @Query("""
        UPDATE competition_entries
        SET userScore = :score, userComment = :comment, userRank = :rank
        WHERE id = :id
    """)
    suspend fun updateUserScore(id: String, score: Int, comment: String, rank: Int?)

    // ── 综合分（finalizeRound 写入）──────────────────────────────

    @Query("UPDATE competition_entries SET compositeScore = :score WHERE id = :id")
    suspend fun updateCompositeScore(id: String, score: Float)

    @Query("DELETE FROM competition_entries WHERE roundId = :roundId")
    suspend fun deleteAllForRound(roundId: String)

    @Query("DELETE FROM competition_entries WHERE id = :id")
    suspend fun deleteById(id: String)
}
