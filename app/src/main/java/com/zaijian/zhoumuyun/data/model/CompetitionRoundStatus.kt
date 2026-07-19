package com.zaijian.zhoumuyun.data.model

/**
 * 竞赛轮次状态常量（裁判与竞争机制 · 竞赛编排）
 *
 * 从 CompetitionRoundManager 中提取（S-5），原因：
 * CompetitionRoundEntity（data.db.entity 层）此前直接 import
 * CompetitionRoundManager.Companion 中的状态常量，形成 db.entity → agent 的反向依赖，
 * 违反了分层原则（Entity 应处于最底层，不应依赖业务逻辑层）。
 * 现将状态常量独立为 data.model 层的纯常量对象，Entity 与 Manager 均改为依赖此处。
 *
 * 状态机说明：
 *   COLLECTING             → 参赛角色正在生成各自的参赛作品（待进入 runCollecting）
 *   COLLECTING_IN_PROGRESS → 收集进行中：runCollecting 已开始，正在生成作品（P-11 新增中间态，
 *                             防止跨进程重入时重复生成，崩溃恢复后重入可继续）
 *   COLLECTED              → 收集完成：所有参赛作品已写入，等待进入评审（P-11 新增终态）
 *   JUDGING                → 裁判评审 + 各角色自评中
 *   AWAITING_USER          → 等待用户打分/排名/评语
 *   COMPLETED              → 用户评分已提交，compositeScore 已算出，奖惩反哺已触发
 *   CANCELLED              → (P3-5) 竞赛被取消/中断，不再参与后续流程
 */
object CompetitionRoundStatus {
    const val STATUS_COLLECTING = "COLLECTING"
    const val STATUS_COLLECTING_IN_PROGRESS = "COLLECTING_IN_PROGRESS"
    const val STATUS_COLLECTED = "COLLECTED"
    const val STATUS_JUDGING = "JUDGING"
    const val STATUS_AWAITING_USER = "AWAITING_USER"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_CANCELLED = "CANCELLED"
}
