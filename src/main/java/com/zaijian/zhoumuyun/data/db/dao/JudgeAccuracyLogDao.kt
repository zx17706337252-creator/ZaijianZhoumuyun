package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.JudgeAccuracyLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * JudgeAccuracyLog DAO（裁判与竞争机制 · 裁判评分吻合度历史）
 *
 * 每次 finalizeRound 后由 CompetitionRoundManager 写入一条。
 * 主要用途：取某裁判最近 N 条记录，判断连续吻合度是否低于阈值，
 * 若是则发圆桌提醒（复用 RoundtableMessageDao.insert）。
 */
@Dao
interface JudgeAccuracyLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: JudgeAccuracyLogEntity)

    @Query("SELECT * FROM judge_accuracy_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): JudgeAccuracyLogEntity?

    /** 取某裁判档案的所有历史记录，时间倒序（最新在前） */
    @Query("SELECT * FROM judge_accuracy_log WHERE judgeProfileId = :judgeProfileId ORDER BY createdAt DESC")
    suspend fun getAllForJudge(judgeProfileId: String): List<JudgeAccuracyLogEntity>

    @Query("SELECT * FROM judge_accuracy_log WHERE judgeProfileId = :judgeProfileId ORDER BY createdAt DESC")
    fun observeAllForJudge(judgeProfileId: String): Flow<List<JudgeAccuracyLogEntity>>

    /**
     * 取某裁判最近 N 条记录（连续低吻合度检测用）。
     * 调用方对结果列表调用 average() / all { it < threshold } 判断。
     */
    @Query("""
        SELECT * FROM judge_accuracy_log
        WHERE judgeProfileId = :judgeProfileId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentForJudge(judgeProfileId: String, limit: Int): List<JudgeAccuracyLogEntity>

    /** 取某轮次的裁判吻合度记录（一轮一条） */
    @Query("SELECT * FROM judge_accuracy_log WHERE roundId = :roundId LIMIT 1")
    suspend fun getByRound(roundId: String): JudgeAccuracyLogEntity?

    @Query("DELETE FROM judge_accuracy_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM judge_accuracy_log WHERE judgeProfileId = :judgeProfileId")
    suspend fun deleteAllForJudge(judgeProfileId: String)
}
