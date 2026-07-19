package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v54 → v55：W1-002 修复——practice_records 补齐圆桌播报补偿标记
 *
 * 背景：DailyPracticeWorker.runSinglePractice() 的完整流程跨
 * practice_records / specialty_profiles / roundtable_messages 三张表，
 * 且包含不可回滚的 LLM 调用，无法用数据库事务整体包裹。此前若进程在
 * "落库"成功后、"圆桌播报"完成前被杀，修炼记录已持久化，但圆桌里的其他
 * 角色永远看不到这条播报消息——因为没有任何字段记录"这条修炼是否已经
 * 播报过"。
 *
 * 本次新增 practice_records.roundtablePosted 列（INTEGER/Boolean，
 * 默认值 1/true），配合 DailyPracticeWorker 新增的"先落 PENDING（0）→
 * 播报成功后落 COMPLETED（1）→ 下次运行开头扫描 0 补发"逻辑，实现最终
 * 一致性。
 *
 * 存量数据默认回填为 1（已播报）：升级前的历史记录必然是旧版 Worker
 * 跑完整个流程（含播报）才落的库，不存在"半途而废"的存量脏数据，回填 1
 * 可避免升级后被误判为 PENDING 而在圆桌里重新刷一遍旧播报。
 *
 * 纯新增可空默认列（ADD COLUMN ... DEFAULT 1），不涉及表重建。
 */
internal val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `practice_records` ADD COLUMN `roundtablePosted` INTEGER NOT NULL DEFAULT 1"
        )
    }
}
