package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.CompetitionRoundStatus

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
 *   CANCELLED              → (P3-5) 竞赛被取消/中断，不再参与后续流程
 */
@Entity(
    tableName = "competition_rounds",
    indices = [
        Index(value = ["projectDomain"]),
        Index(value = ["status"]),
        Index(value = ["completedAt"]),
        Index(value = ["judgeCharacterId", "createdAt"]),
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
    val status: String = CompetitionRoundStatus.STATUS_COLLECTING,

    val createdAt: Long,

    /** COMPLETED 时写入，其他状态为 null */
    val completedAt: Long? = null,

    // A8-1 修复: 裁判圆桌播报跳过标记。postJudgeResultToRoundtable 在 runJudging
    // 阶段调用，当 findMostRecentRoundtableIdForSpeaker 返回 null（裁判从未在任何
    // 圆桌发言）时，播报被静默跳过。此处落 true，供 CompetitionScreen 在
    // STATUS_COMPLETED 展示区读取并提示用户"裁判暂无关联圆桌，评审结果仅在此页展示"。
    val judgeRoundtableBroadcastSkipped: Boolean = false,
)
