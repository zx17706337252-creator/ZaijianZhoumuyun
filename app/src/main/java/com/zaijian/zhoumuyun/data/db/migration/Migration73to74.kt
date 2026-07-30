package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 73 → 74：灵活自动化编排 · 事件落盘兜底（§11.1）
 *
 * 新增一张独立表：pending_events。不改动任何现有表结构，是"纯新增表"的迁移，
 * 与 Migration72to73.kt 同类。
 *
 * pending_events 用于在 EventBus.emit() 之前持久化事件，App 被系统杀掉后
 * 可由 processPendingEvents() 重放。详见《灵活自动化编排·改造设计方案》§11.1。
 *
 * §13.1 写法要点（严格照抄）：
 * - String 主键：`id` TEXT NOT NULL PRIMARY KEY
 * - Boolean 字段：SQLite 无原生 Boolean，Room 用 INTEGER 存储，
 *   `processed` INTEGER NOT NULL DEFAULT 0
 */
internal val MIGRATION_73_74 = object : Migration(73, 74) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pending_events` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `eventName` TEXT NOT NULL,
                `characterId` INTEGER NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `processed` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
        // §11.1 processPendingEvents() 的高频查询路径：WHERE processed = 0 ORDER BY createdAt ASC
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_events_processed` ON `pending_events` (`processed`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_events_eventName` ON `pending_events` (`eventName`)")
    }
}
