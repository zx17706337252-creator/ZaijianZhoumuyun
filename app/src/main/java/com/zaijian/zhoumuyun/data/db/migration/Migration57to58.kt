package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v57 → v58：通知中心已读状态表。
 * 新增 notification_read_state（itemKey TEXT 主键 + readAt INTEGER），
 * 纯新表，不改动任何现有表结构。
 */
internal val MIGRATION_57_58 = object : Migration(57, 58) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notification_read_state` (
                `itemKey` TEXT NOT NULL,
                `readAt` INTEGER NOT NULL,
                PRIMARY KEY(`itemKey`)
            )
            """.trimIndent()
        )
    }
}