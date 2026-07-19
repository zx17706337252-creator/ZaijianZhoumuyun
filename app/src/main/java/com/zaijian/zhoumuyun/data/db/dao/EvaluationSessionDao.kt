package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zaijian.zhoumuyun.data.db.entity.EvaluationSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * EvaluationSession DAO — 打分会话（Phase 24）
 */
@Dao
interface EvaluationSessionDao {

    // ── 写入 ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: EvaluationSessionEntity)

    @Update
    suspend fun update(session: EvaluationSessionEntity)

    // ── 读取：按 ID ───────────────────────────────────────────

    @Query("SELECT * FROM evaluation_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: String): EvaluationSessionEntity?

    // ── 读取：PENDING（等待 Agent B 评审）──────────────────

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE characterId = :characterId AND status = 'PENDING'
        ORDER BY createdAt ASC
        LIMIT 1
    """)
    suspend fun getOldestPending(characterId: Int): EvaluationSessionEntity?

    // ── 读取：REVIEWED（等待用户打分）────────────────────────

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE characterId = :characterId AND status = 'REVIEWED'
        ORDER BY createdAt ASC
        LIMIT 1
    """)
    suspend fun getOldestReviewed(characterId: Int): EvaluationSessionEntity?

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE characterId = :characterId AND status = 'REVIEWED'
        ORDER BY createdAt ASC
        LIMIT 1
    """)
    fun observeOldestReviewed(characterId: Int): Flow<EvaluationSessionEntity?>

    // ── 读取：历史 Session（学习闭环可观测性用）────────────

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE characterId = :characterId AND status = 'SCORED'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getScoredHistory(characterId: Int, limit: Int = 20): List<EvaluationSessionEntity>

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE characterId = :characterId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    fun observeAll(characterId: Int, limit: Int = 50): Flow<List<EvaluationSessionEntity>>

    // ── 读取：按目标 ID（Phase 26 提炼判断用）────────────────

    @Query("""
        SELECT * FROM evaluation_sessions
        WHERE goalId = :goalId AND status = 'SCORED'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getScoredByGoal(goalId: String, limit: Int = 10): List<EvaluationSessionEntity>

    /** 高分（compositeScore ≥ threshold）Session 数量，Phase 26 触发提炼用 */
    @Query("""
        SELECT COUNT(*) FROM evaluation_sessions
        WHERE goalId = :goalId
          AND status = 'SCORED'
          AND compositeScore >= :threshold
    """)
    suspend fun countHighScoreByGoal(goalId: String, threshold: Float = 3.5f): Int

    // ── 状态更新：PENDING → REVIEWED ─────────────────────────

    @Query("""
        UPDATE evaluation_sessions
        SET status         = 'REVIEWED',
            agentScore     = :agentScore,
            agentScoreJson = :agentScoreJson,
            agentComment   = :agentComment,
            reportText     = :reportText,
            updatedAt      = :updatedAt
        WHERE id = :sessionId
    """)
    suspend fun markReviewed(
        sessionId: String,
        agentScore: Float,
        agentScoreJson: String,
        agentComment: String,
        reportText: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    // ── 状态更新：REVIEWED → SCORED ──────────────────────────

    @Query("""
        UPDATE evaluation_sessions
        SET status         = 'SCORED',
            userScore      = :userScore,
            userNote       = :userNote,
            compositeScore = :compositeScore,
            updatedAt      = :updatedAt
        WHERE id = :sessionId
    """)
    suspend fun markScored(
        sessionId: String,
        userScore: Int,
        userNote: String?,
        compositeScore: Float,
        updatedAt: Long = System.currentTimeMillis(),
    )

    // ── 状态更新：→ SKIPPED ───────────────────────────────────

    @Query("""
        UPDATE evaluation_sessions
        SET status    = 'SKIPPED',
            updatedAt = :updatedAt
        WHERE id = :sessionId
    """)
    suspend fun markSkipped(
        sessionId: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    // ── 统计 ──────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM evaluation_sessions WHERE characterId = :characterId AND status = 'SCORED'")
    suspend fun countScored(characterId: Int): Int

    @Query("SELECT AVG(compositeScore) FROM evaluation_sessions WHERE characterId = :characterId AND status = 'SCORED'")
    suspend fun avgCompositeScore(characterId: Int): Float?

    // ── 清理（测试/重置用）───────────────────────────────────

    @Query("DELETE FROM evaluation_sessions WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Int)
}
