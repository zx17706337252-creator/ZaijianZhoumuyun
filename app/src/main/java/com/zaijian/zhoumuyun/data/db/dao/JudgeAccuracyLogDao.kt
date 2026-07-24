package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaijian.zhoumuyun.data.db.entity.JudgeAccuracyLogEntity

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
}
