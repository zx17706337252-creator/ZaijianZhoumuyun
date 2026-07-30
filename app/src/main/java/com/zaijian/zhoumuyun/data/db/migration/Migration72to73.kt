package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 72 → 73：灵活自动化编排（链条系统）
 *
 * 新增两张独立表：chain_definitions、chain_runs。
 * 不改动任何现有表结构，是"纯新增表"的迁移，与 Migration71to72.kt 同类。
 * 详见《灵活自动化编排·改造设计方案》§3、§11。
 *
 * §13.1 写法要点（严格照抄，不能混用）：
 * - String 主键：`id` TEXT NOT NULL PRIMARY KEY（对照 workflow_jobs 表），
 *   不是 `INTEGER PRIMARY KEY AUTOINCREMENT`（那是 agent_store_records 的自增主键写法）。
 * - Boolean 字段：SQLite 无原生 Boolean，Room 用 INTEGER 存储，
 *   对照 workflow_jobs.isReported 的写法：`enabled` INTEGER NOT NULL DEFAULT 1。
 */
internal val MIGRATION_72_73 = object : Migration(72, 73) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── chain_definitions（链条定义）──────────────────────────
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chain_definitions` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `characterId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `triggerType` TEXT NOT NULL,
                `triggerEventName` TEXT,
                `triggerCron` TEXT,
                `nodesJson` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_definitions_characterId` ON `chain_definitions` (`characterId`)")
        // 触发匹配的高频查询路径（§6 ChainTriggerMatcher）：按事件名 + 是否启用筛选
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_definitions_triggerEventName_enabled` ON `chain_definitions` (`triggerEventName`, `enabled`)")

        // ── chain_runs（链条运行实例）────────────────────────────
        // §11.2 修正：新增 lockedUntil 字段，配合 ChainRunDao.claimRun() 做数据库级认领锁
        // §11.6：新增 maxNodeVisits/deadlineAt 双重防护，防止 Check 节点死循环
        // §11.10：新增 isReported 字段，接入未播报机制
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chain_runs` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `chainDefId` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `currentNodeIndex` INTEGER NOT NULL DEFAULT 0,
                `context` TEXT NOT NULL DEFAULT '{}',
                `wakeAtMs` INTEGER,
                `visitCount` INTEGER NOT NULL DEFAULT 0,
                `maxNodeVisits` INTEGER NOT NULL DEFAULT 200,
                `deadlineAt` INTEGER NOT NULL,
                `lockedUntil` INTEGER,
                `isReported` INTEGER NOT NULL DEFAULT 0,
                `startedAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_runs_status` ON `chain_runs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_runs_characterId_status` ON `chain_runs` (`characterId`, `status`)")
        // §11.10 未播报查询的高频路径，对照 workflow_jobs 表 (isReported, status) 组合索引
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_runs_isReported_status` ON `chain_runs` (`isReported`, `status`)")
    }
}
