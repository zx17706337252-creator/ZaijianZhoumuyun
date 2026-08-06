package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 70 → 71：角色间私聊功能（方案_角色间私聊_v2-5）
 *
 * 新增三张独立表：private_chat_pairs、private_chat_messages、private_chat_sessions。
 * 不改动任何现有表结构（包括 roundtable_messages），是"不依赖圆桌机制"在数据库
 * 层面的直接体现。
 *
 * 验收修复：private_chat_messages.id 对应 PrivateChatMessageEntity 的
 * @PrimaryKey(autoGenerate = true)，必须写成单独一行内联的
 * `id` INTEGER PRIMARY KEY AUTOINCREMENT（SQLite rowid 别名），不能拆成
 * NOT NULL 列 + 表级 PRIMARY KEY(id) 约束——那是两种不同的 schema，会导致
 * Room 生成的期望 schema 和迁移后实际 schema 不一致，也会让运行时插入
 * id=0 的新消息无法正确拿到自增 id。写法与项目里 birth_records 表
 * （同样 autoGenerate = true）一致，见 Migrations11to20.kt。
 */
internal val MIGRATION_70_71 = object : Migration(70, 71) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── private_chat_pairs（配对配置）──────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `private_chat_pairs` (
                `pairId` TEXT NOT NULL,
                `characterIdA` INTEGER NOT NULL,
                `characterIdB` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `maxTurnsPerSession` INTEGER NOT NULL,
                `maxSessionsPerDay` INTEGER NOT NULL,
                `cooldownMinutes` INTEGER NOT NULL,
                `sessionsUsedToday` INTEGER NOT NULL,
                `usedTodayResetAt` INTEGER NOT NULL,
                `lastSessionAt` INTEGER NOT NULL,
                PRIMARY KEY(`pairId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_private_chat_pairs_characterIdA_characterIdB` " +
                "ON `private_chat_pairs` (`characterIdA`, `characterIdB`)"
        )

        // ── private_chat_messages（消息本体）────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `private_chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `pairId` TEXT NOT NULL,
                `senderCharacterId` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `sessionId` TEXT NOT NULL,
                `turnIndexInSession` INTEGER NOT NULL,
                `triggerSource` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_private_chat_messages_pairId_timestamp` " +
                "ON `private_chat_messages` (`pairId`, `timestamp`)"
        )

        // ── private_chat_sessions（会话状态，v2.3 新增）──────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `private_chat_sessions` (
                `sessionId` TEXT NOT NULL,
                `pairId` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `turnCount` INTEGER NOT NULL,
                `errorMessage` TEXT,
                PRIMARY KEY(`sessionId`)
            )
            """.trimIndent()
        )
    }
}
