package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A8-1 修复: Migration v77 → v78——competition_rounds 新增 judgeRoundtableBroadcastSkipped 列
 *
 * 背景：CompetitionRoundManager.postJudgeResultToRoundtable() 在 runJudging 阶段
 * 调用 findMostRecentRoundtableIdForSpeaker 查找裁判最近活跃的圆桌。若裁判从未在
 * 任何圆桌发言，返回 null，播报被静默跳过（仅 ZLog.d 后 return），用户在圆桌聊天中
 * 看不到裁判公布评审结果。
 *
 * 本次新增 competition_rounds.judgeRoundtableBroadcastSkipped 列（INTEGER/Boolean，
 * 默认值 0/false）。当 roundtableId 为 null 时由 Manager 置 true，供 CompetitionScreen
 * 在 STATUS_COMPLETED 展示区读取并提示用户"裁判暂无关联圆桌，评审结果仅在此页展示"。
 *
 * 存量数据默认回填为 0（false）：历史已完成的轮次无论是否跳过播报，都不再回溯
 * 提示——该提示只对修复后新跑的轮次有意义。
 *
 * 纯新增非空默认列（ADD COLUMN ... DEFAULT 0），不涉及表重建。
 */
internal val MIGRATION_77_78 = object : Migration(77, 78) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `competition_rounds` ADD COLUMN `judgeRoundtableBroadcastSkipped` INTEGER NOT NULL DEFAULT 0"
        )
    }
}
