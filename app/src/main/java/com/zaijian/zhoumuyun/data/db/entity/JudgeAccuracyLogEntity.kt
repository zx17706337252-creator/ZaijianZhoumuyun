package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 裁判准确度历史记录（裁判与竞争机制 · 裁判评分吻合度追踪）
 *
 * 每次 finalizeRound 后写入一条：记录该轮裁判排名与用户最终评分排名（userScore，
 * 不是 compositeScore——compositeScore 混了裁判自己的分，会污染这个指标）的一致度。
 * 一致度（agreementScore）由 Spearman 秩相关系数 [-1,1] 线性映射到 [0,1] 得到，
 * 手写几行即可，不引入新库。
 *
 * 取值含义：1.0=排名完全一致；0.5=两者排名无相关；0.0=排名完全相反。
 *
 * 用途：连续多轮 agreementScore 低于阈值时，
 * 往该裁判最近活跃的圆桌发提醒，复用 RoundtableMessageDao.insert 播报写法，
 * 提示用户考虑再训练裁判标准。
 */
@Entity(
    tableName = "judge_accuracy_log",
    indices = [
        Index(value = ["judgeProfileId"]),
        Index(value = ["judgeProfileId", "createdAt"]),
    ]
)
data class JudgeAccuracyLogEntity(
    @PrimaryKey val id: String,

    /** 关联的裁判档案 ID（judge_profiles.id） */
    val judgeProfileId: String,

    /** 关联的竞赛轮次 ID（competition_rounds.id） */
    val roundId: String,

    /**
     * 裁判排名与用户最终排名的一致度，Spearman 秩相关系数，范围 0~1。
     * 1.0 = 排名完全一致；0.5 = 排名无相关；0.0 = 排名完全相反。
     */
    val agreementScore: Float,

    val createdAt: Long,
)
