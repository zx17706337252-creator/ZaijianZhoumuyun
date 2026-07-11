package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.CompetitionRoundEntity
import kotlinx.coroutines.flow.Flow

/**
 * CompetitionRound DAO（裁判与竞争机制 · 竞赛轮次）
 *
 * 状态机流转：
 *   COLLECTING → COLLECTING_IN_PROGRESS → COLLECTED → JUDGING → AWAITING_USER → COMPLETED
 * 由 CompetitionRoundManager 驱动，Dao 只提供原子状态更新。
 */
@Dao
interface CompetitionRoundDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(round: CompetitionRoundEntity)

    @Query("SELECT * FROM competition_rounds WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CompetitionRoundEntity?

    @Query("SELECT * FROM competition_rounds WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<CompetitionRoundEntity?>

    /** 按项目方向查询所有轮次，时间倒序（竞赛历史列表用） */
    @Query("SELECT * FROM competition_rounds WHERE projectDomain = :domain ORDER BY createdAt DESC")
    fun observeAllForDomain(domain: String): Flow<List<CompetitionRoundEntity>>

    /** 取所有未完成的轮次（App 启动时恢复中断任务用） */
    @Query("SELECT * FROM competition_rounds WHERE status != 'COMPLETED' ORDER BY createdAt ASC")
    suspend fun getAllPendingRounds(): List<CompetitionRoundEntity>

    /** 取某角色担任裁判的所有轮次，时间倒序（JudgeProfileScreen Section3用） */
    @Query("SELECT * FROM competition_rounds WHERE judgeCharacterId = :characterId ORDER BY createdAt DESC")
    fun observeRoundsAsJudge(characterId: Int): Flow<List<CompetitionRoundEntity>>

    // ── 状态流转 ──────────────────────────────────────────────

    @Query("UPDATE competition_rounds SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE competition_rounds SET status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: String, completedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM competition_rounds WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 某时间点之后已完成的竞赛轮次，供离线简报统计交付评分用（整合方案 v2.1 4.10.1）。 */
    @Query("""
        SELECT * FROM competition_rounds
        WHERE status = 'COMPLETED' AND completedAt >= :after
        ORDER BY completedAt DESC
    """)
    suspend fun getCompletedSince(after: Long): List<CompetitionRoundEntity>
}
