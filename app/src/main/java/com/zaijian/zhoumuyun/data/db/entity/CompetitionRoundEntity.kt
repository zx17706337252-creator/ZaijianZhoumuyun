package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 竞赛轮次实体（裁判与竞争机制 · 竞赛编排）
 *
 * 一轮竞赛由用户手动发起（CompetitionRoundManager.startRound），
 * 不走 DailyPracticeWorker 的定时调度模型。
 *
 * status 状态机：
 *   COLLECTING             → 参赛角色正在生成各自的参赛作品（待进入 runCollecting）
 *   COLLECTING_IN_PROGRESS → 收集进行中：runCollecting 已开始，正在生成作品（P-11 新增中间态，
 *                             防止跨进程重入时重复生成，崩溃恢复后重入可继续）
 *   COLLECTED              → 收集完成：所有参赛作品已写入，等待进入评审（P-11 新增终态）
 *   JUDGING                → 裁判评审 + 各角色自评中
 *   AWAITING_USER          → 等待用户打分/排名/评语
 *   COMPLETED              → 用户评分已提交，compositeScore 已算出，奖惩反哺已触发
 */
@Entity(
    tableName = "competition_rounds",
    indices = [
        Index(value = ["projectDomain"]),
        Index(value = ["status"]),
        // v47→v48（离线简报复核修复）：getCompletedSince() 原先先走 status 索引
        // 缩小范围、再对 completedAt 做全表比较排序。当前数据量级小可接受，
        // 但既然发现了就顺手补上专用索引，避免以后轮次数据变多时退化。
        Index(value = ["completedAt"]),
    ]
)
data class CompetitionRoundEntity(
    @PrimaryKey val id: String,

    /** 项目方向，对应 specialty_profiles.domain */
    val projectDomain: String,

    /** 命题题目，用户在发起表单里填写 */
    val topic: String,

    /**
     * 当轮裁判的 characterId，在对话里临时指定。
     * 不锁死任何角色，每轮独立选择。
     */
    val judgeCharacterId: Int,

    /**
     * 参赛角色 ID 列表，JSON 数组字符串。
     * 如 "[1,3,5]"，由 CompetitionRoundManager.startRound 写入。
     */
    val participantIdsJson: String,

    /** 当前状态，见类注释的状态机说明 */
    val status: String = "COLLECTING",

    val createdAt: Long,

    /** COMPLETED 时写入，其他状态为 null */
    val completedAt: Long? = null,
)
